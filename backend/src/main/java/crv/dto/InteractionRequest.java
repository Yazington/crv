package crv.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A shopper clicked a product.
 *
 * @param shopperId an anonymous id the browser keeps; there are no accounts
 */
public record InteractionRequest(
        @NotBlank @Size(max = 64) String shopperId,
        @NotBlank @Size(max = 16) String productId,
        @NotNull InteractionSource source) {}
