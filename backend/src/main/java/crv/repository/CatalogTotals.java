package crv.repository;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

public record CatalogTotals(
        long products,
        long listings,
        @Nullable BigDecimal minPrice,
        @Nullable BigDecimal maxPrice) {}
