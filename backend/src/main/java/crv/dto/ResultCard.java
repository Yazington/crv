package crv.dto;

import java.util.List;

/**
 * A product card from a search, with how it meets each soft constraint.
 *
 * @param photo what the photo check saw for the requested colour; it only orders results and is never shown as a
 *     fact (AC1.7)
 */
public record ResultCard(ProductCard product, List<ConstraintLabel> labels, PhotoMatch photo) {

    public ResultCard {
        labels = List.copyOf(labels);
    }
}
