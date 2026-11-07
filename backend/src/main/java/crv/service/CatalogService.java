package crv.service;

import crv.dto.BrandCount;
import crv.dto.CatalogFilter;
import crv.dto.CatalogStats;
import crv.dto.ImageDto;
import crv.dto.ProductCard;
import crv.dto.ProductDetail;
import crv.dto.ProductPage;
import crv.dto.ProductSort;
import crv.dto.VariantDetail;
import crv.dto.VariantSummary;
import crv.entity.ImageRef;
import crv.entity.VariantEntity;
import crv.repository.CatalogQueryRepository;
import crv.repository.CatalogTotals;
import crv.repository.ProductMatch;
import crv.repository.ProductRepository;
import crv.repository.VariantRepository;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the catalog: filtered product pages, one product, and counts. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    /** The card shows the most complete matching listing, then the cheapest, then the smallest asin. */
    private static final Comparator<VariantEntity> LEAD_FIRST = Comparator.comparingDouble(VariantEntity::getQuality)
            .reversed()
            .thenComparing(VariantEntity::getPrice)
            .thenComparing(VariantEntity::getAsin);

    private final CatalogQueryRepository queries;
    private final ProductRepository products;
    private final VariantRepository variants;

    public CatalogService(CatalogQueryRepository queries, ProductRepository products, VariantRepository variants) {
        this.queries = queries;
        this.products = products;
        this.variants = variants;
    }

    public ProductPage findProducts(CatalogFilter filter, ProductSort sort, int page, int size) {
        List<ProductMatch> matches = queries.findMatches(filter, sort, (long) page * size, size);
        CatalogTotals totals = queries.totals(filter);
        Map<String, List<VariantEntity>> variantsByProduct =
                variants
                        .findByProductIdInOrderByAsin(
                                matches.stream().map(ProductMatch::productId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                VariantEntity::getProductId, LinkedHashMap::new, Collectors.toList()));
        List<ProductCard> cards = matches.stream()
                .map(match -> card(match, variantsByProduct.getOrDefault(match.productId(), List.of())))
                .toList();
        return new ProductPage(cards, page, size, totals.products(), totals.listings());
    }

    public Optional<ProductDetail> findProduct(String productId) {
        return products.findById(productId).map(product -> {
            List<VariantEntity> all = product.getVariants();
            return new ProductDetail(
                    product.getId(),
                    min(all),
                    max(all),
                    distinct(all, VariantEntity::getColor),
                    distinct(all, VariantEntity::getSize),
                    productImages(all),
                    all.stream().map(CatalogService::detail).toList());
        });
    }

    public CatalogStats stats(CatalogFilter filter, int brandLimit) {
        CatalogTotals totals = queries.totals(filter);
        List<BrandCount> brands = queries.brandTotals(filter, brandLimit).stream()
                .map(brand -> new BrandCount(brand.brand(), brand.products(), brand.listings()))
                .toList();
        return new CatalogStats(totals.products(), totals.listings(), totals.minPrice(), totals.maxPrice(), brands);
    }

    private static ProductCard card(ProductMatch match, List<VariantEntity> all) {
        Set<String> matchingAsins = Set.copyOf(match.matchingAsins());
        VariantEntity lead = all.stream()
                .filter(variant -> matchingAsins.contains(variant.getAsin()))
                .min(LEAD_FIRST)
                .orElseThrow(() -> new IllegalStateException("No variants loaded for product " + match.productId()));
        return new ProductCard(
                match.productId(),
                summary(lead),
                match.minPrice(),
                match.maxPrice(),
                distinct(all, VariantEntity::getColor),
                distinct(all, VariantEntity::getSize),
                all.size(),
                matchingAsins.size());
    }

    private static VariantSummary summary(VariantEntity v) {
        return new VariantSummary(
                v.getAsin(),
                v.getTitle(),
                v.getBrand(),
                v.getColor(),
                v.getSize(),
                v.getPrice(),
                v.getAverageRating(),
                v.getRatingCount(),
                mainImage(v.getImages()));
    }

    private static VariantDetail detail(VariantEntity v) {
        return new VariantDetail(
                v.getAsin(),
                v.getTitle(),
                v.getBrand(),
                v.getColor(),
                v.getSize(),
                v.getPrice(),
                v.getTrustedListPrice(),
                v.getAverageRating(),
                v.getRatingCount(),
                v.getCategories(),
                v.getDepartment(),
                v.getMainCategory(),
                v.getFeatures(),
                v.getDescription(),
                v.getDetails(),
                v.getSpecs(),
                v.getImages().stream().map(CatalogService::image).toList(),
                v.isSharedPhoto(),
                v.getFirstAvailable(),
                v.getQuality(),
                v.getPopularity());
    }

    private static ImageDto image(ImageRef image) {
        return new ImageDto(image.large(), image.hiRes(), image.thumb(), image.variant());
    }

    /** Every photo of the product once, in variant order. */
    private static List<ImageDto> productImages(List<VariantEntity> all) {
        Map<String, ImageDto> byUrl = new LinkedHashMap<>();
        for (VariantEntity variant : all) {
            for (ImageRef ref : variant.getImages()) {
                byUrl.putIfAbsent(ref.large(), image(ref));
            }
        }
        return List.copyOf(byUrl.values());
    }

    /** The image marked MAIN, else the first one. */
    private static @Nullable String mainImage(List<ImageRef> images) {
        return images.stream()
                .filter(image -> "MAIN".equalsIgnoreCase(image.variant()))
                .findFirst()
                .or(() -> images.stream().findFirst())
                .map(ImageRef::large)
                .orElse(null);
    }

    private static List<String> distinct(List<VariantEntity> all, Function<VariantEntity, @Nullable String> field) {
        Set<String> values = new LinkedHashSet<>();
        for (VariantEntity variant : all) {
            String value = field.apply(variant);
            if (value != null) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static BigDecimal min(List<VariantEntity> all) {
        return all.stream()
                .map(VariantEntity::getPrice)
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }

    private static BigDecimal max(List<VariantEntity> all) {
        return all.stream()
                .map(VariantEntity::getPrice)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }
}
