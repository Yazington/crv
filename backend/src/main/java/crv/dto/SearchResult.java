package crv.dto;

import java.util.List;
import java.util.Map;

/**
 * The products found for a query, best first.
 *
 * @param totalProducts products that pass the hard filters and match the text, before soft constraints
 * @param totalListings the variants behind those products
 * @param judged products whose soft constraints were checked
 * @param dropped judged products left out because they clearly don't fit ("no" on a constraint)
 * @param unknown per constraint, how many shown products had no usable catalog value (R5)
 */
public record SearchResult(
        SearchQuery query,
        List<ResultCard> items,
        long totalProducts,
        long totalListings,
        int judged,
        int dropped,
        Map<String, Integer> unknown) {

    public SearchResult {
        items = List.copyOf(items);
        unknown = Map.copyOf(unknown);
    }
}
