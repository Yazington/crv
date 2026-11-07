package crv.dto;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Counts for a set of filters, computed by a database query (R9, AC9.2).
 *
 * @param minPrice null when nothing matches
 * @param maxPrice null when nothing matches
 * @param brands brands with the most matching products first
 */
public record CatalogStats(
        long products,
        long listings,
        @Nullable BigDecimal minPrice,
        @Nullable BigDecimal maxPrice,
        List<BrandCount> brands) {}
