package crv.service;

import crv.entity.ImageRef;
import crv.entity.VariantFields;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Builds cleaned variants for unit tests. */
final class Variants {

    private final String asin;
    private String title = "Plain Tee";
    private @Nullable String store = "Acme";
    private @Nullable String color;
    private @Nullable String size;
    private BigDecimal price = new BigDecimal("20.00");
    private @Nullable BigDecimal listPrice;
    private @Nullable BigDecimal averageRating;
    private @Nullable Integer ratingCount;
    private List<String> categories = List.of("Clothing, Shoes & Jewelry", "Women", "Tops");
    private List<String> features = List.of();
    private List<String> description = List.of();
    private Map<String, String> specs = new LinkedHashMap<>();
    private List<ImageRef> images = new ArrayList<>();

    private Variants(String asin) {
        this.asin = asin;
    }

    static Variants variant(String asin) {
        return new Variants(asin);
    }

    Variants title(String value) {
        title = value;
        return this;
    }

    Variants store(@Nullable String value) {
        store = value;
        return this;
    }

    Variants color(@Nullable String value) {
        color = value;
        return this;
    }

    Variants size(@Nullable String value) {
        size = value;
        return this;
    }

    Variants listPrice(String value) {
        listPrice = new BigDecimal(value);
        return this;
    }

    Variants rating(String rating, int count) {
        averageRating = new BigDecimal(rating);
        ratingCount = count;
        return this;
    }

    Variants categories(String... values) {
        categories = List.of(values);
        return this;
    }

    Variants features(String... values) {
        features = List.of(values);
        return this;
    }

    Variants description(String value) {
        description = List.of(value);
        return this;
    }

    Variants material(String value) {
        specs.put("material", value);
        return this;
    }

    Variants mainPhoto(String url) {
        images.add(new ImageRef(url, null, null, "MAIN"));
        return this;
    }

    Variants image(@Nullable String hiRes) {
        images.add(new ImageRef("https://img.example/" + images.size() + ".jpg", hiRes, null, null));
        return this;
    }

    VariantFields build() {
        return new VariantFields(
                asin,
                title,
                store,
                store,
                color,
                size,
                price,
                listPrice,
                averageRating,
                ratingCount,
                categories,
                null,
                null,
                features,
                description,
                Map.of(),
                specs,
                images,
                null);
    }
}
