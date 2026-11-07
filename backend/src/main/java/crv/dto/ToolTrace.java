package crv.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One tool call in a turn, logged so the acceptance checks can run from the log (AC13.4).
 *
 * @param arguments the arguments as JSON
 * @param productIds the products the call returned, in order
 */
public record ToolTrace(
        String tool,
        String arguments,
        List<String> productIds,
        long millis,
        @Nullable String error) {

    public ToolTrace {
        productIds = List.copyOf(productIds);
    }
}
