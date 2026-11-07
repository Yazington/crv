package crv.service;

import crv.entity.VariantFields;
import java.util.List;

/** The variants of one product, sorted by asin. The product id is the first asin. */
record ProductGroup(String productId, List<VariantFields> variants) {

    ProductGroup {
        variants = List.copyOf(variants);
    }
}
