package crv.service;

import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Conversations in memory, by id. The least recently used are dropped beyond a fixed number. */
@Component
class ConversationStore {

    private static final int LIMIT = 1_000;

    private final Clock clock;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    ConversationStore(Clock clock) {
        this.clock = clock;
    }

    /** The conversation with this id, or a new one when the id is null or unknown. */
    Conversation open(@Nullable String id) {
        Conversation existing = id == null ? null : conversations.get(id);
        if (existing != null) {
            existing.touch(clock.instant());
            return existing;
        }
        if (conversations.size() >= LIMIT) {
            conversations.values().stream()
                    .min(Comparator.comparing(Conversation::lastUsed))
                    .ifPresent(oldest -> conversations.remove(oldest.id()));
        }
        Conversation created = new Conversation(UUID.randomUUID().toString(), clock.instant());
        conversations.put(created.id(), created);
        return created;
    }
}
