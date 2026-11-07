package crv.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One card per product (R8).
 *
 * @param lead the matching variant the card shows
 * @param minPrice lowest price among the matching variants
 * @param maxPrice highest price among the matching variants
 * @param colors every colour the catalog lists for the product ("Seen in")
 * @param sizes every size the catalog lists for the product
 */
public record ProductCard(
        String productId,
        VariantSummary lead,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        List<String> colors,
        List<String> sizes,
        int variantCount,
        int matchingVariantCount) {}
