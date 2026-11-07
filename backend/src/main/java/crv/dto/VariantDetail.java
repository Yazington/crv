package crv.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every field the catalog has for one variant. Null means "Not listed".
 *
 * @param listPrice the list price, unless the plausibility screen marked it suspect
 * @param sharedPhoto the main photo is also used by a differently coloured variant, so it may show another
 *     colour
 * @param details the catalog's details table, as listed
 * @param specs flat spec fields such as material and style
 */
public record VariantDetail(
        String asin,
        String title,
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
        List<ImageDto> images,
        boolean sharedPhoto,
        @Nullable LocalDate firstAvailable,
        double quality,
        double popularity) {}
