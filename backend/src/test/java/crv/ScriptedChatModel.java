package crv;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A stand-in for DeepSeek in tests. Agent turns (prompts with a system message) answer from a script the test
 * sets. Judge and photo prompts answer by simple, predictable rules. Real model behaviour is covered by the eval
 * runs against Fireworks (requirements.md, N2).
 */
public class ScriptedChatModel implements ChatModel {

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern NUMBERED = Pattern.compile("(?m)^(\\d+): (.*)$");
    private static final Pattern TITLE_END = Pattern.compile(" \\| ");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final Deque<Object> script = new ConcurrentLinkedDeque<>();
    private final List<Prompt> agentPrompts = new CopyOnWriteArrayList<>();
    private final AtomicInteger judgeCalls = new AtomicInteger();

    /** The agent's next replies, in order: {@link AssistantMessage}s, or exceptions to throw. */
    public void script(Object... steps) {
        script.clear();
        agentPrompts.clear();
        script.addAll(List.of(steps));
    }

    public List<Prompt> agentPrompts() {
        return List.copyOf(agentPrompts);
    }

    public int judgeCalls() {
        return judgeCalls.get();
    }

    public static AssistantMessage toolCall(String name, String arguments) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments)))
                .build();
    }

    public static AssistantMessage say(String text) {
        return AssistantMessage.builder().content(text).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (prompt.getInstructions().stream().anyMatch(SystemMessage.class::isInstance)) {
            agentPrompts.add(prompt);
            Object next = script.poll();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            AssistantMessage message = next instanceof AssistantMessage scripted ? scripted : say("Done.");
            return new ChatResponse(List.of(new Generation(message)));
        }
        judgeCalls.incrementAndGet();
        String text = prompt.getUserMessage().getText();
        return new ChatResponse(List.of(new Generation(say(answer(text == null ? "" : text)))));
    }

    private static String answer(String prompt) {
        if (prompt.startsWith("Does the product in this photo")) {
            return "{\"shows\": \"no\"}";
        }
        Matcher quoted = QUOTED.matcher(prompt);
        String wanted = quoted.find() ? quoted.group(1).toLowerCase(Locale.ROOT) : "";
        List<String> answers = new ArrayList<>();
        Matcher line = NUMBERED.matcher(prompt);
        while (line.find()) {
            String value = line.group(2).toLowerCase(Locale.ROOT);
            String verdict;
            if (prompt.contains("wants the colour")) {
                verdict = value.equals(wanted) ? "match" : value.contains(wanted) ? "partial" : "no";
            } else if (prompt.contains("wants size")) {
                verdict = value.equals("one size")
                        ? "unknown"
                        : value.equals(wanted) || (wanted.equals("small") && value.equals("s")) ? "match" : "no";
            } else {
                verdict = sameKind(wanted, TITLE_END.split(value, 2)[0]) ? "match" : "no";
            }
            answers.add("\"" + line.group(1) + "\": \"" + verdict + "\"");
        }
        return "```json\n{" + String.join(", ", answers) + "}\n```";
    }

    /** Every word the shopper used appears in the title, comparing the first four letters. */
    private static boolean sameKind(String wanted, String title) {
        for (String word : SPACES.split(wanted, -1)) {
            if (!title.contains(word.substring(0, Math.min(4, word.length())))) {
                return false;
            }
        }
        return true;
    }
}
