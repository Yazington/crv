package crv.dto;

import java.util.List;

/**
 * One page of product cards, with totals across all pages.
 *
 * @param totalProducts products with at least one matching variant
 * @param totalListings matching variants
 */
public record ProductPage(List<ProductCard> items, int page, int size, long totalProducts, long totalListings) {}
