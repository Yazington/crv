package crv.repository;

import java.math.BigDecimal;
import java.util.List;

/** A product with at least one variant that passes the filters. */
public record ProductMatch(String productId, BigDecimal minPrice, BigDecimal maxPrice, List<String> matchingAsins) {

    public ProductMatch {
        matchingAsins = List.copyOf(matchingAsins);
    }
}
