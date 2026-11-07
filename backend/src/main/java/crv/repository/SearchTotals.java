package crv.repository;

import java.util.List;

/** Counts, price range and top brands for a search. */
public record SearchTotals(CatalogTotals totals, List<BrandTotals> brands) {

    public SearchTotals {
        brands = List.copyOf(brands);
    }
}
