package crv.dto;

import org.jspecify.annotations.Nullable;

/**
 * One soft constraint judged for one product.
 *
 * @param constraint "kind", "color" or "size"
 * @param wanted what the shopper asked for, for example "orange"
 * @param value the catalog value it was judged on; null when the catalog doesn't list one
 */
public record ConstraintLabel(
        String constraint,
        String wanted,
        ConstraintMatch match,
        @Nullable String value) {}
