package crv.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The cleaned fields of one catalog record. Null means the catalog doesn't list the value.
 *
 * @param store the raw store name, used for grouping
 * @param brand the brand shown to shoppers: the store, falling back to the brand field
 */
public record VariantFields(
        String asin,
        String title,
        @Nullable String store,
        @Nullable String brand,
        @Nullable String color,
        @Nullable String size,
        BigDecimal price,
        @Nullable BigDecimal listPrice,
        @Nullable BigDecimal averageRating,
        @Nullable Integer ratingCount,
        List<String> categories,
        @Nullable String department,
        @Nullable String mainCategory,
        List<String> features,
        List<String> description,
        Map<String, String> details,
        Map<String, String> specs,
        List<ImageRef> images,
        @Nullable LocalDate firstAvailable) {

    public VariantFields {
        categories = List.copyOf(categories);
        features = List.copyOf(features);
        description = List.copyOf(description);
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        specs = Collections.unmodifiableMap(new LinkedHashMap<>(specs));
        images = List.copyOf(images);
    }
}
