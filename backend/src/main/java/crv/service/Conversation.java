package crv.service;

import crv.dto.ResultCard;
import crv.dto.SearchQuery;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;

/**
 * One shopper's conversation, kept in memory: the messages so far, the active search (the UI's pills), and the
 * products shown last, so follow-ups like "anything cheaper?" and "the second one" refer to them (R4).
 * Callers lock on the conversation while they use it.
 */
final class Conversation {

    private final String id;
    private List<Message> history = new ArrayList<>();
    private @Nullable SearchQuery activeQuery;
    private List<ResultCard> lastShown = List.of();
    private Instant lastUsed;

    Conversation(String id, Instant now) {
        this.id = id;
        this.lastUsed = now;
    }

    String id() {
        return id;
    }

    List<Message> history() {
        return history;
    }

    void history(List<Message> messages) {
        this.history = new ArrayList<>(messages);
    }

    @Nullable
    SearchQuery activeQuery() {
        return activeQuery;
    }

    List<ResultCard> lastShown() {
        return lastShown;
    }

    /** Records a search the shopper now sees. */
    void shown(SearchQuery query, List<ResultCard> items) {
        this.activeQuery = query;
        this.lastShown = List.copyOf(items);
    }

    Instant lastUsed() {
        return lastUsed;
    }

    void touch(Instant now) {
        this.lastUsed = now;
    }
}
