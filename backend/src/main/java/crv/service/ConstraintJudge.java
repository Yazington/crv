package crv.service;

import crv.dto.ConstraintMatch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Judges soft constraints at runtime with DeepSeek, thinking off (docs/data-and-search.md, "Runtime settings").
 * Colour and size are judged per distinct catalog value, so one label covers every variant that shares it, and
 * each value is judged once and cached. When the model fails or skips a value, the label is UNKNOWN, never MATCH.
 */
@Component
public class ConstraintJudge {

    private static final Logger LOG = LoggerFactory.getLogger(ConstraintJudge.class);
    private static final int CACHE_LIMIT = 50_000;

    private static final String COLOR_PROMPT = """
            The shopper wants the colour "%s".
            For each numbered colour label from a product catalog, answer:
            "match" if it is that colour, "partial" if that colour is one of several colours or a close shade,
            "no" if it is another colour, or "unknown" if the label is not a colour (for example a code).
            Reply with JSON only, like {"1": "match", "2": "no"}.
            Labels:
            %s""";

    private static final String SIZE_PROMPT = """
            The shopper wants size "%s".
            For each numbered size label from a product catalog, answer "match" only if the label itself names
            that size, "no" if it names another size, or "unknown" if it is not a size or is "One Size".
            For example, for "small": "S", "Small" and "US S" match; "Medium", "Large" and "X-Small" do not.
            Judge the label, not the product.
            Reply with JSON only, like {"1": "match", "2": "no"}.
            Labels:
            %s""";

    private final ChatModel chat;
    private final JsonMapper json;
    private final AssistantProperties settings;
    private final Map<String, ConstraintMatch> cache = new ConcurrentHashMap<>();

    public ConstraintJudge(ChatModel chat, JsonMapper json, AssistantProperties settings) {
        this.chat = chat;
        this.json = json;
        this.settings = settings;
    }

    /** Labels each catalog colour label against the colour the shopper wants. */
    public Map<String, ConstraintMatch> colors(String wanted, Collection<String> labels) {
        return judgeValues("color", COLOR_PROMPT, wanted, labels);
    }

    /** Labels each catalog size label against the size the shopper wants. */
    public Map<String, ConstraintMatch> sizes(String wanted, Collection<String> labels) {
        return judgeValues("size", SIZE_PROMPT, wanted, labels);
    }

    /**
     * Labels whether each product is the kind of product the shopper wants.
     *
     * @param descriptions product id to "title | category"
     */
    public Map<String, ConstraintMatch> kinds(String wanted, Map<String, String> descriptions) {
        List<String> ids = List.copyOf(descriptions.keySet());
        List<String> lines = ids.stream().map(descriptions::get).toList();
        String prompt = """
                The shopper is looking for: "%s".
                For each numbered product (title, then category), answer whether it IS that kind of product:
                "match" if it is, "partial" if it is close (for example a set that includes it), "no" if it is
                something else. Judge only the kind of product. Ignore colour, size, brand and price.
                Reply with JSON only, like {"1": "match", "2": "no"}.
                Products:
                %s""".formatted(wanted, numbered(lines));
        Map<Integer, ConstraintMatch> byNumber = ask(prompt, ids.size());
        Map<String, ConstraintMatch> labels = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            labels.put(ids.get(i), byNumber.getOrDefault(i + 1, ConstraintMatch.UNKNOWN));
        }
        return labels;
    }

    private Map<String, ConstraintMatch> judgeValues(
            String constraint, String prompt, String wanted, Collection<String> labels) {
        String want = wanted.strip().toLowerCase(Locale.ROOT);
        Map<String, ConstraintMatch> result = new LinkedHashMap<>();
        List<String> todo = new ArrayList<>();
        for (String label : labels) {
            ConstraintMatch known = cache.get(key(constraint, want, label));
            if (known != null) {
                result.put(label, known);
            } else if (!todo.contains(label)) {
                todo.add(label);
            }
        }
        if (!todo.isEmpty()) {
            Map<Integer, ConstraintMatch> byNumber = ask(prompt.formatted(wanted.strip(), numbered(todo)), todo.size());
            if (cache.size() > CACHE_LIMIT) {
                cache.clear();
            }
            for (int i = 0; i < todo.size(); i++) {
                ConstraintMatch match = byNumber.get(i + 1);
                if (match != null) {
                    cache.put(key(constraint, want, todo.get(i)), match);
                }
                result.put(todo.get(i), match == null ? ConstraintMatch.UNKNOWN : match);
            }
        }
        return result;
    }

    /** Asks the model and reads its numbered JSON answer. Missing or unreadable answers are left out. */
    private Map<Integer, ConstraintMatch> ask(String prompt, int count) {
        Map<Integer, ConstraintMatch> answers = new HashMap<>();
        try {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(settings.model())
                    .reasoningEffort(settings.judgeReasoningEffort())
                    .temperature(0.0)
                    .maxTokens(Math.max(400, count * 20))
                    .build();
            ChatResponse response = chat.call(new Prompt(List.of(new UserMessage(prompt)), options));
            String text = text(response);
            JsonNode node = json.readTree(jsonPart(text));
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                ConstraintMatch match = parse(entry.getValue().asString(""));
                Integer number = number(entry.getKey());
                if (match != null && number != null) {
                    answers.put(number, match);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Constraint judge failed; labels fall back to unknown: {}", e.toString());
        }
        return answers;
    }

    static String text(ChatResponse response) {
        Generation result = response.getResult();
        if (result == null) {
            return "";
        }
        String text = result.getOutput().getText();
        return text == null ? "" : text;
    }

    /** The first JSON object in a reply, which may be wrapped in a code fence. */
    static String jsonPart(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : "{}";
    }

    private static @Nullable ConstraintMatch parse(String answer) {
        return switch (answer.strip().toLowerCase(Locale.ROOT)) {
            case "match", "yes" -> ConstraintMatch.MATCH;
            case "partial", "partly" -> ConstraintMatch.PARTIAL;
            case "no" -> ConstraintMatch.NO;
            case "unknown" -> ConstraintMatch.UNKNOWN;
            default -> null;
        };
    }

    private static @Nullable Integer number(String key) {
        try {
            return Integer.valueOf(key.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String numbered(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            out.append(i + 1)
                    .append(": ")
                    .append(lines.get(i).replace('\n', ' '))
                    .append('\n');
        }
        return out.toString();
    }

    private static String key(String constraint, String wanted, String label) {
        return constraint + '\u0000' + wanted + '\u0000' + label;
    }
}
