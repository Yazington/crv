package crv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * One shopper message.
 *
 * @param conversationId null to start a new conversation
 */
public record ChatRequest(
        @Nullable @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 2000) String message) {}
