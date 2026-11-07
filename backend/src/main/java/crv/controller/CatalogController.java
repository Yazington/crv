package crv.controller;

import crv.dto.CatalogFilter;
import crv.dto.CatalogStats;
import crv.dto.ProductDetail;
import crv.dto.ProductPage;
import crv.dto.ProductSort;
import crv.service.CatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /** Products with at least one variant that passes every filter, one card per product. */
    @GetMapping("/products")
    public ProductPage listProducts(
            @Valid @ParameterObject CatalogFilter filter,
            @RequestParam(defaultValue = "POPULARITY") ProductSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "24") @Min(1) @Max(100) int size) {
        return catalog.findProducts(filter, sort, page, size);
    }

    /** One product with every field of every variant. */
    @GetMapping("/products/{productId}")
    public ResponseEntity<ProductDetail> getProduct(@PathVariable String productId) {
        return ResponseEntity.of(catalog.findProduct(productId));
    }

    /** Product and listing counts, price range and top brands for a set of filters. */
    @GetMapping("/catalog/stats")
    public CatalogStats stats(
            @Valid @ParameterObject CatalogFilter filter,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int brandLimit) {
        return catalog.stats(filter, brandLimit);
    }
}
