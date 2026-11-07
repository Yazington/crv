package crv.dto;

import org.jspecify.annotations.Nullable;

public record ImageDto(
        String large,
        @Nullable String hiRes,
        @Nullable String thumb,
        @Nullable String variant) {}
