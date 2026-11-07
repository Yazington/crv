package crv.service;

import crv.entity.ImageRef;
import crv.entity.VariantFields;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Flags shared photos (docs/data-and-search.md, "Photos and colour"). A variant's main photo is shared when it is
 * also the main photo of a variant of the same product with a different colour, so it can't show both colours.
 * Variants with no colour listed don't count as a different colour.
 */
final class SharedPhotos {

    private SharedPhotos() {}

    /** The asins, within one product, whose main photo is shared with a differently coloured variant. */
    static Set<String> flag(List<VariantFields> product) {
        Map<String, Set<String>> coloursByPhoto = new HashMap<>();
        for (VariantFields variant : product) {
            String photo = mainPhoto(variant);
            String colour = variant.color();
            if (photo != null && colour != null) {
                coloursByPhoto.computeIfAbsent(photo, url -> new HashSet<>()).add(colour.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> shared = new HashSet<>();
        for (VariantFields variant : product) {
            String photo = mainPhoto(variant);
            if (photo != null && coloursByPhoto.getOrDefault(photo, Set.of()).size() > 1) {
                shared.add(variant.asin());
            }
        }
        return shared;
    }

    private static @Nullable String mainPhoto(VariantFields variant) {
        return variant.images().stream()
                .filter(image -> "MAIN".equalsIgnoreCase(image.variant()))
                .map(ImageRef::large)
                .findFirst()
                .orElse(null);
    }
}
