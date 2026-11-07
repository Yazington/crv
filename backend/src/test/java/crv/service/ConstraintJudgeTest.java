package crv.service;

import static org.assertj.core.api.Assertions.assertThat;

import crv.dto.ConstraintMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

class ConstraintJudgeTest {

    static final AssistantProperties SETTINGS =
            new AssistantProperties("test-model", "low", "none", 5, 30, 10, 10, 1.0, 0.3, 0.3, 6);

    /** Replies with fixed text and records the prompts it was sent. */
    static final class FixedModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        final String reply;

        FixedModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (reply.equals("fail")) {
                throw new IllegalStateException("model down");
            }
            return new ChatResponse(List.of(
                    new Generation(AssistantMessage.builder().content(reply).build())));
        }
    }

    @Test
    void labelsEachValueByItsNumberAndAsksOnlyOnce() {
        FixedModel model = new FixedModel("```json\n{\"1\": \"match\", \"2\": \"partial\", \"3\": \"no\"}\n```");
        ConstraintJudge judge = new ConstraintJudge(model, JsonMapper.builder().build(), SETTINGS);

        Map<String, ConstraintMatch> labels = judge.colors("Orange", List.of("Orange", "Orange/Black", "Blue"));
        Map<String, ConstraintMatch> again = judge.colors("orange", List.of("Blue", "Orange"));

        assertThat(labels)
                .containsExactly(
                        Map.entry("Orange", ConstraintMatch.MATCH),
                        Map.entry("Orange/Black", ConstraintMatch.PARTIAL),
                        Map.entry("Blue", ConstraintMatch.NO));
        assertThat(again).containsEntry("Blue", ConstraintMatch.NO).containsEntry("Orange", ConstraintMatch.MATCH);
        assertThat(model.prompts).hasSize(1);
        assertThat(model.prompts.get(0).getUserMessage().getText())
                .contains("wants the colour \"Orange\"")
                .contains("1: Orange\n2: Orange/Black\n3: Blue");
    }

    @Test
    void missingAndUnreadableAnswersAreUnknownNeverMatch() {
        FixedModel model = new FixedModel("{\"1\": \"match\", \"2\": \"maybe\"}");
        ConstraintJudge judge = new ConstraintJudge(model, JsonMapper.builder().build(), SETTINGS);

        assertThat(judge.sizes("small", List.of("S", "Medium", "L")))
                .containsExactly(
                        Map.entry("S", ConstraintMatch.MATCH),
                        Map.entry("Medium", ConstraintMatch.UNKNOWN),
                        Map.entry("L", ConstraintMatch.UNKNOWN));
    }

    @Test
    void aFailingModelLeavesEverythingUnknownAndIsAskedAgainLater() {
        FixedModel model = new FixedModel("fail");
        ConstraintJudge judge = new ConstraintJudge(model, JsonMapper.builder().build(), SETTINGS);

        assertThat(judge.kinds("running shoes", Map.of("P1", "Trail Runner Shoe | Running")))
                .containsExactly(Map.entry("P1", ConstraintMatch.UNKNOWN));
        assertThat(judge.colors("red", List.of("Red"))).containsExactly(Map.entry("Red", ConstraintMatch.UNKNOWN));
        judge.colors("red", List.of("Red"));
        assertThat(model.prompts).hasSize(3);
    }

    @Test
    void kindsAreLabelledPerProduct() {
        FixedModel model = new FixedModel("{\"1\": \"match\", \"2\": \"no\"}");
        ConstraintJudge judge = new ConstraintJudge(model, JsonMapper.builder().build(), SETTINGS);
        java.util.LinkedHashMap<String, String> products = new java.util.LinkedHashMap<>();
        products.put("P1", "Trail Runner Shoe | Running > Trail Running");
        products.put("P2", "Shoe Laces | Shoe Care & Accessories");

        assertThat(judge.kinds("running shoes", products))
                .containsEntry("P1", ConstraintMatch.MATCH)
                .containsEntry("P2", ConstraintMatch.NO);
    }
}
