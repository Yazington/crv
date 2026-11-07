package crv.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One product with every variant.
 *
 * @param images every photo of the product, without repeats: photos belong to the product, not to one colour
 */
public record ProductDetail(
        String productId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        List<String> colors,
        List<String> sizes,
        List<ImageDto> images,
        List<VariantDetail> variants) {}
