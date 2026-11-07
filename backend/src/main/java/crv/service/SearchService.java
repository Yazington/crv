package crv.service;

import crv.dto.BrandCount;
import crv.dto.CatalogFilter;
import crv.dto.CatalogStats;
import crv.dto.ConstraintLabel;
import crv.dto.ConstraintMatch;
import crv.dto.PhotoMatch;
import crv.dto.ResultCard;
import crv.dto.SearchQuery;
import crv.dto.SearchResult;
import crv.entity.VariantEntity;
import crv.repository.CatalogTotals;
import crv.repository.SearchRepository;
import crv.repository.SearchTotals;
import crv.repository.VariantHit;
import crv.repository.VariantRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds products for a shopper's query (docs/data-and-search.md, "Search flow"):
 *
 * <ol>
 *   <li>exact hard filters, plus full-text search on the kind of product;
 *   <li>products ranked by relevance, then quality and popularity (R11);
 *   <li>the shortlist judged at runtime: is it the right kind, the right colour, the right size (R3);
 *   <li>products that clearly don't fit dropped, the rest ordered best match first;
 *   <li>when a colour was asked for, the top photos checked, and photos showing it moved first (AC1.6).
 * </ol>
 */
@Service
public class SearchService {

    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);
    private static final int HIT_LIMIT = 3000;
    /** Ranking bonus before judging when the catalog's colour or size text contains the shopper's words. */
    private static final double COLOR_HINT = 0.5;

    private static final double SIZE_HINT = 0.25;

    /** When too few products fit, the next batch is judged too, up to this many batches (AC3.5). */
    private static final int JUDGE_ROUNDS = 3;

    private static final String KIND = "kind";
    private static final String COLOR = "color";
    private static final String SIZE = "size";

    /** Among equally matching variants: the most complete listing, then the cheapest, then the smallest asin. */
    private static final Comparator<VariantEntity> BEST_LISTING = Comparator.comparingDouble(VariantEntity::getQuality)
            .reversed()
            .thenComparing(VariantEntity::getPrice)
            .thenComparing(VariantEntity::getAsin);

    private final SearchRepository search;
    private final VariantRepository variants;
    private final ConstraintJudge judge;
    private final PhotoCheck photos;
    private final AssistantProperties settings;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public SearchService(
            SearchRepository search,
            VariantRepository variants,
            ConstraintJudge judge,
            PhotoCheck photos,
            AssistantProperties settings) {
        this.search = search;
        this.variants = variants;
        this.judge = judge;
        this.photos = photos;
        this.settings = settings;
    }

    public SearchResult search(SearchQuery query) {
        long start = System.nanoTime();
        CatalogFilter filter = query.filterOrNone();
        String text = blankToNull(query.text());
        String color = blankToNull(query.color());
        String size = blankToNull(query.size());

        List<VariantHit> hits = search.hits(filter, text, false, HIT_LIMIT);
        boolean anyWord = false;
        if (text != null && productIds(hits).size() < settings.results()) {
            hits = search.hits(filter, text, true, HIT_LIMIT);
            anyWord = true;
        }
        long totalProducts = productIds(hits).size();
        long totalListings = hits.size();
        if (!anyWord && hits.size() >= HIT_LIMIT) {
            CatalogTotals totals = search.totals(filter, text, 1).totals();
            totalProducts = totals.products();
            totalListings = totals.listings();
        }

        List<Ranked> ranked = rank(hits, color, size);
        long found = System.nanoTime();
        List<List<Ranked>> batches = new ArrayList<>();
        for (int from = 0; from < ranked.size() && batches.size() < JUDGE_ROUNDS; from += settings.shortlist()) {
            batches.add(ranked.subList(from, Math.min(from + settings.shortlist(), ranked.size())));
        }
        List<Candidate> candidates = new ArrayList<>();
        int dropped = 0;
        int judgedProducts = 0;
        if (color != null || size != null) {
            // Colour and size requests are selective: judge every batch at once rather than one after another.
            List<Future<Judged>> pending = new ArrayList<>();
            for (List<Ranked> batch : batches) {
                pending.add(pool.submit(() -> judge(batch, text, color, size)));
            }
            for (int i = 0; i < pending.size(); i++) {
                Judged judged = awaitJudged(pending.get(i));
                candidates.addAll(judged.kept());
                dropped += judged.dropped();
                judgedProducts += batches.get(i).size();
            }
        } else {
            for (List<Ranked> batch : batches) {
                if (candidates.size() >= settings.results()) {
                    break;
                }
                Judged judged = judge(batch, text, color, size);
                candidates.addAll(judged.kept());
                dropped += judged.dropped();
                judgedProducts += batch.size();
            }
        }
        long judgedAt = System.nanoTime();
        candidates.sort(Comparator.comparingInt(Candidate::tier)
                .thenComparing(
                        Comparator.comparingDouble((Candidate c) -> c.product().score())
                                .reversed()));
        if (color != null) {
            int before = candidates.size();
            candidates = photoFirst(color, candidates);
            dropped += before - candidates.size();
        }
        LOG.info(
                "search text={} color={} size={} products={} judged={} dropped={} ms: find={} judge={} photos={}",
                text,
                color,
                size,
                totalProducts,
                judgedProducts,
                dropped,
                (found - start) / 1_000_000,
                (judgedAt - found) / 1_000_000,
                (System.nanoTime() - judgedAt) / 1_000_000);

        List<ResultCard> items = candidates.stream()
                .limit(settings.results())
                .map(c -> new ResultCard(Cards.card(c.lead(), c.matching(), c.all()), c.labels(), c.photo()))
                .toList();
        return new SearchResult(
                query, items, totalProducts, totalListings, judgedProducts, dropped, unknownCounts(items));
    }

    /**
     * Judges one batch of products at runtime: the kind of product, and the colour and size of their matching
     * variants, all in parallel. Each product's lead is its best-matching variant. Products with a "no" are
     * dropped.
     */
    private Judged judge(List<Ranked> batch, @Nullable String text, @Nullable String color, @Nullable String size) {
        Map<String, List<VariantEntity>> all = loadVariants(batch);
        Map<String, List<VariantEntity>> matching = new HashMap<>();
        for (Ranked product : batch) {
            matching.put(
                    product.productId(),
                    all.getOrDefault(product.productId(), List.of()).stream()
                            .filter(v -> product.asins().contains(v.getAsin()))
                            .toList());
        }

        Future<Map<String, ConstraintMatch>> kinds = null;
        Future<Map<String, ConstraintMatch>> colors = null;
        Future<Map<String, ConstraintMatch>> sizes = null;
        if (text != null) {
            String wanted = text;
            Map<String, String> described = descriptions(batch, matching);
            kinds = pool.submit(() -> judge.kinds(wanted, described));
        }
        if (color != null) {
            String wanted = color;
            Set<String> labels = values(matching, VariantEntity::getColor);
            colors = pool.submit(() -> judge.colors(wanted, labels));
        }
        if (size != null) {
            String wanted = size;
            Set<String> labels = values(matching, VariantEntity::getSize);
            sizes = pool.submit(() -> judge.sizes(wanted, labels));
        }
        Map<String, ConstraintMatch> kindLabels = await(kinds);
        Map<String, ConstraintMatch> colorLabels = await(colors);
        Map<String, ConstraintMatch> sizeLabels = await(sizes);

        List<Candidate> kept = new ArrayList<>();
        int dropped = 0;
        for (Ranked product : batch) {
            List<VariantEntity> matched = matching.getOrDefault(product.productId(), List.of());
            if (matched.isEmpty()) {
                continue;
            }
            VariantEntity lead = matched.stream()
                    .min(Comparator.<VariantEntity>comparingInt(v -> rank(label(colorLabels, color, v.getColor()))
                                    + rank(label(sizeLabels, size, v.getSize())))
                            .thenComparing(BEST_LISTING))
                    .orElseThrow();
            List<ConstraintLabel> labels = new ArrayList<>();
            if (text != null) {
                labels.add(new ConstraintLabel(
                        KIND,
                        text,
                        kindLabels.getOrDefault(product.productId(), ConstraintMatch.UNKNOWN),
                        lead.getTitle()));
            }
            if (color != null) {
                labels.add(
                        new ConstraintLabel(COLOR, color, label(colorLabels, color, lead.getColor()), lead.getColor()));
            }
            if (size != null) {
                labels.add(new ConstraintLabel(SIZE, size, label(sizeLabels, size, lead.getSize()), lead.getSize()));
            }
            if (labels.stream().anyMatch(l -> l.match() == ConstraintMatch.NO)) {
                dropped++;
                continue;
            }
            kept.add(new Candidate(
                    product,
                    lead,
                    matched,
                    all.getOrDefault(product.productId(), matched),
                    labels,
                    PhotoMatch.NOT_CHECKED));
        }
        return new Judged(kept, dropped);
    }

    /**
     * Products like the given one (R12): the same deepest category, sharing title words, without the product
     * itself or any result whose main photo repeats an earlier one.
     */
    public SearchResult similar(String productId) {
        List<VariantEntity> own = variants.findByProductIdInOrderByAsin(List.of(productId));
        SearchQuery echo = new SearchQuery(null, null, null, null);
        if (own.isEmpty()) {
            return new SearchResult(echo, List.of(), 0, 0, 0, 0, Map.of());
        }
        VariantEntity lead = own.stream().min(BEST_LISTING).orElseThrow();
        List<String> path = lead.getCategories();
        String leaf = path.get(path.size() - 1);
        String words = Arrays.stream(lead.getTitle().split("\\s+")).limit(8).collect(Collectors.joining(" "));
        CatalogFilter sameKind = new CatalogFilter(null, null, null, null, null, null, List.of(leaf), null, null);
        SearchQuery query = new SearchQuery(words, sameKind, null, null);

        List<VariantHit> hits = search.hits(sameKind, words, true, HIT_LIMIT).stream()
                .filter(hit -> !hit.productId().equals(productId))
                .toList();
        List<Ranked> ranked = rank(hits, null, null).stream()
                .limit((long) settings.results() * 3)
                .toList();
        Map<String, List<VariantEntity>> all = loadVariants(ranked);
        Set<String> seenPhotos = new HashSet<>();
        String ownPhoto = Cards.mainImage(lead);
        if (ownPhoto != null) {
            seenPhotos.add(ownPhoto);
        }
        List<ResultCard> items = new ArrayList<>();
        for (Ranked product : ranked) {
            List<VariantEntity> productVariants = all.getOrDefault(product.productId(), List.of());
            List<VariantEntity> matched = productVariants.stream()
                    .filter(v -> product.asins().contains(v.getAsin()))
                    .toList();
            if (matched.isEmpty()) {
                continue;
            }
            VariantEntity best = matched.stream().min(BEST_LISTING).orElseThrow();
            String photo = Cards.mainImage(best);
            if (photo != null && !seenPhotos.add(photo)) {
                continue;
            }
            items.add(new ResultCard(Cards.card(best, matched, productVariants), List.of(), PhotoMatch.NOT_CHECKED));
            if (items.size() == settings.results()) {
                break;
            }
        }
        return new SearchResult(query, items, productIds(hits).size(), hits.size(), 0, 0, Map.of());
    }

    /** Counts, price range and top brands for the filters and text: questions about the whole catalog (AC9.2). */
    public CatalogStats stats(CatalogFilter filter, @Nullable String text, int brandLimit) {
        SearchTotals totals = search.totals(filter, blankToNull(text), brandLimit);
        CatalogTotals counts = totals.totals();
        return new CatalogStats(
                counts.products(),
                counts.listings(),
                counts.minPrice(),
                counts.maxPrice(),
                totals.brands().stream()
                        .map(b -> new BrandCount(b.brand(), b.products(), b.listings()))
                        .toList());
    }

    /**
     * Products by score: relevance against the best match, then quality and popularity (R11). Before judging,
     * variants whose colour or size text contains the shopper's words rank higher, so likely matches make the
     * shortlist. The judge still decides.
     */
    private List<Ranked> rank(List<VariantHit> hits, @Nullable String color, @Nullable String size) {
        double best = hits.stream().mapToDouble(VariantHit::relevance).max().orElse(0);
        Map<String, Ranked> byProduct = new LinkedHashMap<>();
        for (VariantHit hit : hits) {
            double relevance = best > 0 ? hit.relevance() / best : 0;
            double score = settings.relevanceWeight() * relevance
                    + settings.qualityWeight() * hit.quality()
                    + settings.popularityWeight() * hit.popularity()
                    + (mentions(hit.color(), color) ? COLOR_HINT : 0)
                    + (mentions(hit.size(), size) ? SIZE_HINT : 0);
            byProduct.merge(
                    hit.productId(),
                    new Ranked(hit.productId(), score, Set.of(hit.asin())),
                    (a, b) -> new Ranked(a.productId(), Math.max(a.score(), b.score()), union(a.asins(), b.asins())));
        }
        return byProduct.values().stream()
                .sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::productId))
                .toList();
    }

    /** Whether the catalog text contains the shopper's word, ignoring case. */
    private static boolean mentions(@Nullable String catalogText, @Nullable String wanted) {
        return catalogText != null
                && wanted != null
                && catalogText.toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT));
    }

    /**
     * Moves products whose photo shows the colour to the front of the checked top results, keeping order otherwise
     * (AC1.6). A product whose colour text isn't a usable colour is dropped when its photo shows another colour.
     */
    private List<Candidate> photoFirst(String color, List<Candidate> candidates) {
        int checked = Math.min(settings.photoChecks(), candidates.size());
        Map<String, String> urls = new LinkedHashMap<>();
        for (Candidate c : candidates.subList(0, checked)) {
            String url = Cards.mainImage(c.lead());
            if (url != null) {
                urls.put(c.product().productId(), url);
            }
        }
        Map<String, PhotoMatch> seen = photos.check(color, urls);
        List<Candidate> top = new ArrayList<>();
        for (Candidate c : candidates.subList(0, checked)) {
            top.add(c.withPhoto(seen.getOrDefault(c.product().productId(), PhotoMatch.NOT_CHECKED)));
        }
        // When the colour text isn't usable, the photo is the only evidence: drop it if it shows another colour.
        top.removeIf(c -> c.photo() == PhotoMatch.NO && c.colorMatch() == ConstraintMatch.UNKNOWN);
        top.sort(Comparator.comparingInt(c -> photoRank(c.photo())));
        List<Candidate> ordered = new ArrayList<>(top);
        ordered.addAll(candidates.subList(checked, candidates.size()));
        return ordered;
    }

    private Map<String, List<VariantEntity>> loadVariants(List<Ranked> products) {
        return variants
                .findByProductIdInOrderByAsin(
                        products.stream().map(Ranked::productId).toList())
                .stream()
                .collect(Collectors.groupingBy(VariantEntity::getProductId, LinkedHashMap::new, Collectors.toList()));
    }

    private static Map<String, String> descriptions(List<Ranked> shortlist, Map<String, List<VariantEntity>> matching) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (Ranked product : shortlist) {
            matching.getOrDefault(product.productId(), List.of()).stream()
                    .min(BEST_LISTING)
                    .ifPresent(v -> descriptions.put(product.productId(), v.getTitle() + " | " + Cards.category(v)));
        }
        return descriptions;
    }

    private static Set<String> values(
            Map<String, List<VariantEntity>> matching, Function<VariantEntity, @Nullable String> field) {
        Set<String> values = new LinkedHashSet<>();
        for (List<VariantEntity> variants : matching.values()) {
            for (VariantEntity v : variants) {
                String value = field.apply(v);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /** The label for one catalog value; a missing value is UNKNOWN (R5). */
    private static ConstraintMatch label(
            Map<String, ConstraintMatch> labels, @Nullable String wanted, @Nullable String value) {
        if (wanted == null) {
            return ConstraintMatch.MATCH;
        }
        if (value == null) {
            return ConstraintMatch.UNKNOWN;
        }
        return labels.getOrDefault(value, ConstraintMatch.UNKNOWN);
    }

    private static int rank(ConstraintMatch match) {
        return switch (match) {
            case MATCH -> 0;
            case PARTIAL -> 1;
            case UNKNOWN -> 2;
            case NO -> 10;
        };
    }

    private static int photoRank(PhotoMatch photo) {
        return switch (photo) {
            case YES -> 0;
            case PARTLY, NOT_CHECKED -> 1;
            case NO -> 2;
        };
    }

    private static Map<String, Integer> unknownCounts(List<ResultCard> items) {
        Map<String, Integer> unknown = new LinkedHashMap<>();
        for (ResultCard item : items) {
            for (ConstraintLabel label : item.labels()) {
                if (label.match() == ConstraintMatch.UNKNOWN) {
                    unknown.merge(label.constraint(), 1, Integer::sum);
                }
            }
        }
        return unknown;
    }

    private static Map<String, ConstraintMatch> await(@Nullable Future<Map<String, ConstraintMatch>> future) {
        if (future == null) {
            return Map.of();
        }
        try {
            return future.get();
        } catch (ExecutionException e) {
            return Map.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        }
    }

    private static Judged awaitJudged(Future<Judged> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Judging a batch failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while judging", e);
        }
    }

    private static Set<String> productIds(List<VariantHit> hits) {
        return hits.stream().map(VariantHit::productId).collect(Collectors.toSet());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> both = new HashSet<>(a);
        both.addAll(b);
        return both;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** One judged batch: the products kept, and how many were dropped. */
    private record Judged(List<Candidate> kept, int dropped) {}

    /** A product from the full-text step, with the variants that matched. */
    private record Ranked(String productId, double score, Set<String> asins) {}

    /** A shortlisted product after judging. */
    private record Candidate(
            Ranked product,
            VariantEntity lead,
            List<VariantEntity> matching,
            List<VariantEntity> all,
            List<ConstraintLabel> labels,
            PhotoMatch photo) {

        /** Lower is better: the sum of each label's rank. */
        int tier() {
            return labels.stream().mapToInt(l -> rank(l.match())).sum();
        }

        Candidate withPhoto(PhotoMatch seen) {
            return new Candidate(product, lead, matching, all, labels, seen);
        }

        ConstraintMatch colorMatch() {
            return labels.stream()
                    .filter(l -> l.constraint().equals(COLOR))
                    .map(ConstraintLabel::match)
                    .findFirst()
                    .orElse(ConstraintMatch.MATCH);
        }
    }
}
