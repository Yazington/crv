package crv.dto;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** The variant a card shows (requirements.md, AC10.2). Null fields are shown as "Not listed". */
public record VariantSummary(
        String asin,
        String title,
        @Nullable String brand,
        @Nullable String color,
        @Nullable String size,
        BigDecimal price,
        @Nullable BigDecimal averageRating,
        @Nullable Integer ratingCount,
        @Nullable String imageUrl) {}
