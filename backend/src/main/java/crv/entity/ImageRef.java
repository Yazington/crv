package crv.entity;

import org.jspecify.annotations.Nullable;

/** One product photo, stored as JSON on the variant. */
public record ImageRef(
        String large,
        @Nullable String hiRes,
        @Nullable String thumb,
        @Nullable String variant) {}
