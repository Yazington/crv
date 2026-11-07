package crv.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * What the shopper is looking for: the kind of product, hard filters and soft constraints (docs/data-and-search.md,
 * "Search flow"). The UI shows the set fields as removable pills (AC4.7).
 *
 * @param text the kind of product in plain words, for example "running shoes"; no price, brand or colour
 * @param filter hard filters; null means none
 * @param color a colour the shopper asked for, judged against the catalog's colour text
 * @param size a size the shopper asked for, judged against the catalog's size text
 */
public record SearchQuery(
        @Nullable @Size(max = 200) String text,
        @Nullable @Valid CatalogFilter filter,
        @Nullable @Size(max = 60) String color,
        @Nullable @Size(max = 60) String size) {

    public CatalogFilter filterOrNone() {
        return filter == null ? CatalogFilter.none() : filter;
    }
}
