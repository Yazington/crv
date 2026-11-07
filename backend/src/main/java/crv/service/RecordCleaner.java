package crv.service;

import crv.entity.ImageRef;
import crv.entity.VariantFields;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

/**
 * Format-only cleaning of one raw catalog record (docs/data-and-search.md, "Cleaning at ingest"). It uses no
 * vocabulary, so new data passes through unchanged.
 */
final class RecordCleaner {

    /** Placeholder strings the catalog uses for a missing value. */
    private static final Set<String> MISSING = Set.of("nan", "none", "null", "");

    private static final BigDecimal MAX_RATING = BigDecimal.valueOf(5);

    /** Flat spec fields copied from the top level of the record. */
    private static final List<String> SPEC_FIELDS = List.of(
            "material",
            "style",
            "manufacturer",
            "item_model_number",
            "product_dimensions",
            "item_weight",
            "package_dimensions",
            "age_range_(description)",
            "country_of_origin");

    private final LocalDate today;

    RecordCleaner(LocalDate today) {
        this.today = today;
    }

    CleanOutcome clean(JsonNode record) {
        String asin = text(record.path("parent_asin"));
        if (asin == null) {
            return new CleanOutcome.Skipped(SkipReason.NO_ID);
        }
        String title = text(record.path("title"));
        if (title == null) {
            return new CleanOutcome.Skipped(SkipReason.NO_TITLE);
        }
        BigDecimal price = positive(decimal(record.path("price")));
        if (price == null) {
            return new CleanOutcome.Skipped(SkipReason.NO_PRICE);
        }
        String store = text(record.path("store"));
        String rawBrand = text(record.path("brand"));
        String department = text(record.path("department"));
        return new CleanOutcome.Kept(new VariantFields(
                asin,
                title,
                store,
                store != null ? store : rawBrand,
                text(record.path("color")),
                text(record.path("size")),
                price.setScale(2, RoundingMode.HALF_UP),
                scaled(positive(decimal(record.path("original_price"))), 2),
                rating(decimal(record.path("average_rating"))),
                count(record.path("rating_number")),
                texts(record.path("categories")),
                department == null ? null : department.toLowerCase(Locale.ROOT),
                text(record.path("main_category")),
                texts(record.path("features")),
                texts(record.path("description")),
                details(record.path("details")),
                specs(record),
                images(record.path("images")),
                date(record.path("date_first_available"))));
    }

    /** Unescapes HTML entities and trims. Placeholders such as "nan" become null. */
    static @Nullable String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || node.isContainer()) {
            return null;
        }
        String value = HtmlUtils.htmlUnescape(node.isString() ? node.stringValue() : node.asString())
                .strip();
        return MISSING.contains(value.toLowerCase(Locale.ROOT)) ? null : value;
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = text(item);
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /** Nested values, such as best-seller ranks, become "key: value; key: value". */
    private static Map<String, String> details(JsonNode node) {
        Map<String, String> details = new LinkedHashMap<>();
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String value = entry.getValue().isObject() ? flatten(entry.getValue()) : text(entry.getValue());
                String key = HtmlUtils.htmlUnescape(entry.getKey()).strip();
                if (value != null && !key.isEmpty()) {
                    details.put(key, value);
                }
            }
        }
        return details;
    }

    private static @Nullable String flatten(JsonNode object) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            String value = text(entry.getValue());
            if (value != null) {
                parts.add(entry.getKey().strip() + ": " + value);
            }
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static Map<String, String> specs(JsonNode record) {
        Map<String, String> specs = new LinkedHashMap<>();
        for (String field : SPEC_FIELDS) {
            String value = text(record.path(field));
            if (value != null) {
                specs.put(field, value);
            }
        }
        return specs;
    }

    private static List<ImageRef> images(JsonNode node) {
        List<ImageRef> images = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode image : node) {
                String large = text(image.path("large"));
                if (large != null) {
                    images.add(new ImageRef(
                            large, text(image.path("hi_res")), text(image.path("thumb")), text(image.path("variant"))));
                }
            }
        }
        return images;
    }

    private static @Nullable BigDecimal decimal(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String value = text(node);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable BigDecimal positive(@Nullable BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static @Nullable BigDecimal scaled(@Nullable BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static @Nullable BigDecimal rating(@Nullable BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_RATING) > 0) {
            return null;
        }
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    private static @Nullable Integer count(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0) {
            return node.intValue();
        }
        return null;
    }

    /** Epoch milliseconds to a date. Dates after today become null. */
    private @Nullable LocalDate date(JsonNode node) {
        if (!node.isIntegralNumber()) {
            return null;
        }
        LocalDate date = LocalDate.ofInstant(Instant.ofEpochMilli(node.longValue()), ZoneOffset.UTC);
        return date.isAfter(today) ? null : date;
    }
}
