package crv.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Hard filters on reliable fields (requirements.md, R2). Every set filter must hold for a variant to match. A
 * product matches when at least one of its variants does.
 *
 * @param priceBelow "under $X": price < X
 * @param priceAtMost "up to $X": price <= X
 * @param priceAbove "over $X": price > X
 * @param priceAtLeast "from $X": price >= X
 * @param minRating "4 stars and up": average rating >= X
 * @param brand brand name, ignoring case
 * @param categories category nodes; a variant matches when its path contains any of them
 * @param listPriceBelow trusted list price < X; variants without one are left out
 * @param listPriceAtMost trusted list price <= X; variants without one are left out
 */
public record CatalogFilter(
        @Nullable @PositiveOrZero BigDecimal priceBelow,
        @Nullable @PositiveOrZero BigDecimal priceAtMost,
        @Nullable @PositiveOrZero BigDecimal priceAbove,
        @Nullable @PositiveOrZero BigDecimal priceAtLeast,
        @Nullable @DecimalMin("0.0") @DecimalMax("5.0") BigDecimal minRating,
        @Nullable String brand,
        @Nullable List<String> categories,
        @Nullable @PositiveOrZero BigDecimal listPriceBelow,
        @Nullable @PositiveOrZero BigDecimal listPriceAtMost) {

    public static CatalogFilter none() {
        return new CatalogFilter(null, null, null, null, null, null, null, null, null);
    }
}
