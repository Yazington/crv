package crv.service;

import crv.dto.ProductCard;
import crv.dto.VariantSummary;
import crv.entity.ImageRef;
import crv.entity.VariantEntity;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Builds product cards for search results: one card per product, led by the best-matching variant (R8). */
final class Cards {

    private Cards() {}

    /**
     * @param lead the variant the card shows
     * @param matching the variants that passed the search; the price range covers these
     * @param all every variant of the product; "Seen in" colours and sizes come from these
     */
    static ProductCard card(VariantEntity lead, List<VariantEntity> matching, List<VariantEntity> all) {
        List<VariantEntity> priced = matching.isEmpty() ? List.of(lead) : matching;
        return new ProductCard(
                lead.getProductId(),
                summary(lead),
                priced.stream()
                        .map(VariantEntity::getPrice)
                        .min(Comparator.naturalOrder())
                        .orElse(lead.getPrice()),
                priced.stream()
                        .map(VariantEntity::getPrice)
                        .max(Comparator.naturalOrder())
                        .orElse(lead.getPrice()),
                distinct(all, VariantEntity::getColor),
                distinct(all, VariantEntity::getSize),
                all.size(),
                priced.size());
    }

    static VariantSummary summary(VariantEntity v) {
        return new VariantSummary(
                v.getAsin(),
                v.getTitle(),
                v.getBrand(),
                v.getColor(),
                v.getSize(),
                v.getPrice(),
                v.getAverageRating(),
                v.getRatingCount(),
                mainImage(v));
    }

    /** The photo marked MAIN, else the first one. */
    static @Nullable String mainImage(VariantEntity v) {
        List<ImageRef> images = v.getImages();
        return images.stream()
                .filter(image -> "MAIN".equalsIgnoreCase(image.variant()))
                .findFirst()
                .or(() -> images.stream().findFirst())
                .map(ImageRef::large)
                .orElse(null);
    }

    /** The deepest category nodes, for telling the model what kind of product this is. */
    static String category(VariantEntity v) {
        List<String> path = v.getCategories();
        return String.join(" > ", path.subList(Math.max(1, path.size() - 3), path.size()));
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
}
