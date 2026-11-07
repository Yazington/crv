package crv.repository;

import org.jspecify.annotations.Nullable;

/**
 * A variant that matched a search.
 *
 * @param relevance full-text rank of the variant against the shopper's text; 0 when there was no text
 * @param color the catalog's colour text, for ranking before the shortlist is judged
 * @param size the catalog's size text, for ranking before the shortlist is judged
 */
public record VariantHit(
        String asin,
        String productId,
        double relevance,
        double quality,
        double popularity,
        @Nullable String color,
        @Nullable String size) {}
