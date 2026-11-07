package crv.service;

import crv.dto.FeedBasis;
import crv.dto.HomeFeed;
import crv.dto.InteractionRequest;
import crv.dto.PhotoMatch;
import crv.dto.ResultCard;
import crv.entity.VariantEntity;
import crv.repository.InteractionRepository;
import crv.repository.VariantRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The homepage feed (docs/data-and-search.md, "Recommendations"). A shopper with no clicks sees random products.
 * After clicks, from the homepage or the chat, the next homepage shows products like the last few clicked, taking
 * turns between them, and never products already clicked.
 */
@Service
public class RecommendationService {

    /** How many recent clicks the feed is built from. */
    private static final int RECENT_CLICKS = 5;

    private static final Comparator<VariantEntity> BEST_LISTING = Comparator.comparingDouble(VariantEntity::getQuality)
            .reversed()
            .thenComparing(VariantEntity::getPrice)
            .thenComparing(VariantEntity::getAsin);

    private final InteractionRepository interactions;
    private final VariantRepository variants;
    private final SearchService search;

    public RecommendationService(InteractionRepository interactions, VariantRepository variants, SearchService search) {
        this.interactions = interactions;
        this.variants = variants;
        this.search = search;
    }

    /** Records a click. Returns false when the product doesn't exist. */
    public boolean record(InteractionRequest request) {
        if (!interactions.productExists(request.productId())) {
            return false;
        }
        interactions.record(
                request.shopperId().strip(),
                request.productId(),
                request.source().name());
        return true;
    }

    public HomeFeed home(@Nullable String shopperId, int size) {
        List<String> recent = shopperId == null || shopperId.isBlank()
                ? List.of()
                : interactions.recentProducts(shopperId.strip(), RECENT_CLICKS);
        if (recent.isEmpty()) {
            return new HomeFeed(FeedBasis.RANDOM, List.of(), random(size, Set.of()));
        }
        Set<String> clicked = new HashSet<>(recent);
        List<List<ResultCard>> alike =
                recent.stream().map(id -> search.similar(id).items()).toList();
        Map<String, ResultCard> picked = new LinkedHashMap<>();
        int longest = alike.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest && picked.size() < size; i++) {
            for (List<ResultCard> cards : alike) {
                if (i < cards.size() && picked.size() < size) {
                    ResultCard card = cards.get(i);
                    String id = card.product().productId();
                    if (!clicked.contains(id)) {
                        picked.putIfAbsent(id, card);
                    }
                }
            }
        }
        List<ResultCard> items = new ArrayList<>(picked.values());
        if (items.size() < size) {
            Set<String> skip = new HashSet<>(clicked);
            skip.addAll(picked.keySet());
            items.addAll(random(size - items.size(), skip));
        }
        return new HomeFeed(FeedBasis.HISTORY, recent, items);
    }

    /** Random products, without the skipped ones, each led by its most complete listing. */
    private List<ResultCard> random(int size, Set<String> skip) {
        List<String> ids = interactions.randomProducts(size + skip.size()).stream()
                .filter(id -> !skip.contains(id))
                .limit(size)
                .toList();
        Map<String, List<VariantEntity>> byProduct = variants.findByProductIdInOrderByAsin(ids).stream()
                .collect(Collectors.groupingBy(VariantEntity::getProductId));
        List<ResultCard> cards = new ArrayList<>();
        for (String id : ids) {
            List<VariantEntity> all = byProduct.getOrDefault(id, List.of());
            all.stream()
                    .min(BEST_LISTING)
                    .ifPresent(lead ->
                            cards.add(new ResultCard(Cards.card(lead, all, all), List.of(), PhotoMatch.NOT_CHECKED)));
        }
        return cards;
    }
}
