package crv.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The assistant's answer to one message.
 *
 * @param products the products to show, best first; null when this turn didn't search, so the grid stays as it was
 * @param quickReplies short answers to offer as chips when the assistant asked a question (AC6.1)
 * @param constraints the active search, shown as removable pills (AC4.7)
 * @param error set when the assistant couldn't answer; no products are shown then (AC13.3)
 */
public record ChatReply(
        String conversationId,
        String reply,
        @Nullable List<ResultCard> products,
        List<String> quickReplies,
        @Nullable SearchQuery constraints,
        List<ToolTrace> trace,
        long millis,
        @Nullable String error) {}
