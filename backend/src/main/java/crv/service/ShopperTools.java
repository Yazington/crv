package crv.service;

import crv.dto.CatalogFilter;
import crv.dto.CatalogStats;
import crv.dto.ConstraintLabel;
import crv.dto.ProductCard;
import crv.dto.ProductDetail;
import crv.dto.ResultCard;
import crv.dto.SearchQuery;
import crv.dto.SearchResult;
import crv.dto.ToolTrace;
import crv.dto.VariantDetail;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools DeepSeek may call during one shopper turn (docs/data-and-search.md, "Search flow"). Each call is timed
 * and logged with its arguments and the products it returned (AC13.4). The turn keeps the last search, which the
 * UI shows, and any quick replies the model offered.
 */
final class ShopperTools {

    private static final Logger LOG = LoggerFactory.getLogger(ShopperTools.class);
    private static final Map<String, String> AUDIENCES =
            Map.of("women", "Women", "men", "Men", "boys", "Boys", "girls", "Girls", "baby", "Baby");
    private static final int SEEN_IN_LIMIT = 8;
    private static final int TEXT_LIMIT = 800;

    private final SearchService search;
    private final CatalogService catalog;
    private final JsonMapper json;
    private final List<ToolTrace> trace = new ArrayList<>();
    private @Nullable SearchResult shown;
    private List<String> quickReplies = List.of();

    ShopperTools(SearchService search, CatalogService catalog, JsonMapper json) {
        this.search = search;
        this.catalog = catalog;
        this.json = json;
    }

    @Tool(
            name = "search_products",
            description = "Search the catalog with all of the shopper's current constraints. Returns up to 10"
                    + " products, best first, one per product. Each has 'matches': how it meets the kind,"
                    + " colour and size asked for (match, partial or unknown; unknown means the catalog doesn't"
                    + " list it).")
    public SearchView searchProducts(
            @ToolParam(
                            description = "The kind of product in plain words, for example 'running shoes' or"
                                    + " 'rain jacket'. No price, brand, colour or size.")
                    String query,
            @ToolParam(required = false, description = "Highest price in USD.") @Nullable Double maxPrice,
            @ToolParam(
                            required = false,
                            description =
                                    "true for 'up to' or 'at most'; false (default) for 'under' or 'less" + " than'.")
                    @Nullable
                    Boolean maxPriceInclusive,
            @ToolParam(required = false, description = "Lowest price in USD.") @Nullable Double minPrice,
            @ToolParam(required = false, description = "Lowest average rating, from 0 to 5.") @Nullable
                    Double minRating,
            @ToolParam(required = false, description = "Brand name.") @Nullable String brand,
            @ToolParam(required = false, description = "Who it is for: women, men, boys, girls or baby.") @Nullable
                    String audience,
            @ToolParam(required = false, description = "A colour the shopper asked for.") @Nullable String colour,
            @ToolParam(required = false, description = "A size the shopper asked for, as they said it.") @Nullable
                    String size,
            @ToolParam(
                            required = false,
                            description = "Highest list price in USD. Only when the shopper asks about list price.")
                    @Nullable
                    Double maxListPrice) {
        CatalogFilter filter = new CatalogFilter(
                Boolean.TRUE.equals(maxPriceInclusive) ? null : money(maxPrice),
                Boolean.TRUE.equals(maxPriceInclusive) ? money(maxPrice) : null,
                null,
                money(minPrice),
                money(minRating),
                blankToNull(brand),
                audience(audience),
                money(maxListPrice),
                null);
        SearchQuery searchQuery = new SearchQuery(query, filter, blankToNull(colour), blankToNull(size));
        Map<String, @Nullable Object> args = new LinkedHashMap<>();
        args.put("query", query);
        args.put("maxPrice", maxPrice);
        args.put("maxPriceInclusive", maxPriceInclusive);
        args.put("minPrice", minPrice);
        args.put("minRating", minRating);
        args.put("brand", brand);
        args.put("audience", audience);
        args.put("colour", colour);
        args.put("size", size);
        args.put("maxListPrice", maxListPrice);
        SearchResult result = timed("search_products", args, () -> search.search(searchQuery));
        shown = result;
        return view(result);
    }

    @Tool(
            name = "get_product",
            description = "Every catalog field for one product: features, description, details, specs, every"
                    + " colour and size listed. A null field means the catalog doesn't list it.")
    public @Nullable ProductFacts getProduct(@ToolParam(description = "The product id from a search.") String id) {
        Optional<ProductDetail> detail = timed("get_product", Map.of("id", id), () -> catalog.findProduct(id));
        return detail.map(ShopperTools::facts).orElse(null);
    }

    @Tool(
            name = "catalog_stats",
            description = "Counts, price range and top brands for the whole catalog or a part of it. Use for"
                    + " questions like 'how many Nike products do you have?' or 'what brands of running shoes"
                    + " do you carry?'.")
    public CatalogStats catalogStats(
            @ToolParam(required = false, description = "The kind of product in plain words, if any.") @Nullable
                    String query,
            @ToolParam(required = false, description = "Brand name.") @Nullable String brand,
            @ToolParam(required = false, description = "Who it is for: women, men, boys, girls or baby.") @Nullable
                    String audience,
            @ToolParam(required = false, description = "Highest price in USD.") @Nullable Double maxPrice) {
        CatalogFilter filter = new CatalogFilter(
                money(maxPrice), null, null, null, null, blankToNull(brand), audience(audience), null, null);
        Map<String, @Nullable Object> args = new LinkedHashMap<>();
        args.put("query", query);
        args.put("brand", brand);
        args.put("audience", audience);
        args.put("maxPrice", maxPrice);
        return timed("catalog_stats", args, () -> search.stats(filter, query, 10));
    }

    @Tool(
            name = "similar_products",
            description = "Products like a given product: the same kind, not the product itself.")
    public SearchView similarProducts(@ToolParam(description = "The product id from a search.") String id) {
        SearchResult result = timed("similar_products", Map.of("id", id), () -> search.similar(id));
        shown = result;
        return view(result);
    }

    @Tool(
            name = "suggest_replies",
            description = "Offer 2 to 4 short answers as buttons, whenever you ask the shopper a question.")
    public String suggestReplies(
            @ToolParam(description = "2 to 4 short answers, a few words each.") List<String> options) {
        quickReplies = options.stream()
                .map(String::strip)
                .filter(option -> !option.isEmpty())
                .limit(4)
                .toList();
        timed("suggest_replies", Map.of("options", quickReplies), () -> "ok");
        return "Shown as buttons.";
    }

    /** The last search or similar-products call in this turn: what the shopper sees. */
    @Nullable
    SearchResult shown() {
        return shown;
    }

    List<String> quickReplies() {
        return quickReplies;
    }

    List<ToolTrace> trace() {
        return List.copyOf(trace);
    }

    private <T> T timed(String tool, Map<String, ? extends @Nullable Object> args, Supplier<T> call) {
        long start = System.nanoTime();
        String arguments = json.writeValueAsString(args);
        try {
            T result = call.get();
            long millis = (System.nanoTime() - start) / 1_000_000;
            List<String> ids = productIds(result);
            trace.add(new ToolTrace(tool, arguments, ids, millis, null));
            LOG.info("tool={} args={} products={} ms={}", tool, arguments, ids, millis);
            return result;
        } catch (RuntimeException e) {
            long millis = (System.nanoTime() - start) / 1_000_000;
            trace.add(new ToolTrace(tool, arguments, List.of(), millis, e.toString()));
            LOG.warn("tool={} args={} failed after {} ms: {}", tool, arguments, millis, e.toString());
            throw e;
        }
    }

    private static List<String> productIds(@Nullable Object result) {
        if (result instanceof SearchResult found) {
            return found.items().stream()
                    .map(item -> item.product().productId())
                    .toList();
        }
        if (result instanceof Optional<?> maybe && maybe.orElse(null) instanceof ProductDetail detail) {
            return List.of(detail.productId());
        }
        return List.of();
    }

    private static SearchView view(SearchResult result) {
        List<ProductBrief> products =
                result.items().stream().map(ShopperTools::brief).toList();
        List<String> notes = new ArrayList<>();
        result.unknown()
                .forEach((constraint, count) -> notes.add(
                        count + " of these don't list a usable " + constraint + " in the catalog (labelled unknown)."));
        if (result.dropped() > 0) {
            notes.add(result.dropped() + " other products were left out because they are not the kind, colour or"
                    + " size asked for.");
        }
        if (products.isEmpty()) {
            notes.add("No products matched. Say which constraint is likely the cause and offer to relax it.");
        }
        return new SearchView(result.totalProducts(), products, notes);
    }

    private static ProductBrief brief(ResultCard item) {
        ProductCard card = item.product();
        Map<String, String> matches = new LinkedHashMap<>();
        for (ConstraintLabel label : item.labels()) {
            matches.put(label.constraint(), label.match().name().toLowerCase(Locale.ROOT));
        }
        return new ProductBrief(
                card.productId(),
                card.lead().title(),
                card.lead().brand(),
                card.minPrice(),
                card.maxPrice(),
                card.lead().averageRating(),
                card.lead().ratingCount(),
                card.lead().color(),
                card.lead().size(),
                card.colors().stream().limit(SEEN_IN_LIMIT).toList(),
                card.sizes().stream().limit(SEEN_IN_LIMIT).toList(),
                matches);
    }

    private static ProductFacts facts(ProductDetail detail) {
        VariantDetail first = detail.variants().get(0);
        return new ProductFacts(
                detail.productId(),
                first.title(),
                first.brand(),
                detail.minPrice(),
                detail.maxPrice(),
                first.averageRating(),
                first.ratingCount(),
                detail.colors(),
                detail.sizes(),
                first.features().stream().limit(10).toList(),
                clip(String.join(" ", first.description())),
                first.details(),
                first.specs(),
                first.categories());
    }

    private static @Nullable String clip(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= TEXT_LIMIT ? trimmed : trimmed.substring(0, TEXT_LIMIT) + "…";
    }

    private static @Nullable BigDecimal money(@Nullable Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static @Nullable List<String> audience(@Nullable String audience) {
        if (audience == null) {
            return null;
        }
        String node = AUDIENCES.get(audience.strip().toLowerCase(Locale.ROOT));
        return node == null ? null : List.of(node);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** What a search returns to the model. */
    record SearchView(long totalMatchingProducts, List<ProductBrief> products, List<String> notes) {}

    /** One product as the model sees it. Null fields aren't listed in the catalog. */
    record ProductBrief(
            String id,
            String title,
            @Nullable String brand,
            BigDecimal lowestPrice,
            BigDecimal highestPrice,
            @Nullable BigDecimal rating,
            @Nullable Integer reviews,
            @Nullable String colour,
            @Nullable String size,
            List<String> seenInColours,
            List<String> seenInSizes,
            Map<String, String> matches) {}

    /** Every field the model may quote about one product. Null fields aren't listed in the catalog. */
    record ProductFacts(
            String id,
            String title,
            @Nullable String brand,
            BigDecimal lowestPrice,
            BigDecimal highestPrice,
            @Nullable BigDecimal rating,
            @Nullable Integer reviews,
            List<String> colours,
            List<String> sizes,
            List<String> features,
            @Nullable String description,
            Map<String, String> details,
            Map<String, String> specs,
            List<String> categories) {}
}
