package crv;

import static crv.ScriptedChatModel.say;
import static crv.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The assistant end to end against a real Postgres loaded with the raw-format sample (see CatalogApiTest): search,
 * similar products and chat over HTTP. The model is {@link ScriptedChatModel}, so results are predictable.
 */
@SpringBootTest(properties = "crv.ingest.file=" + CatalogApiTest.SAMPLE)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AssistantApiTest.Model.class})
class AssistantApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Model {
        @Bean
        @Primary
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    private final MockMvcTester mvc;
    private final ScriptedChatModel model;

    AssistantApiTest(@Autowired MockMvcTester mvc, @Autowired ScriptedChatModel model) {
        this.mvc = mvc;
        this.model = model;
    }

    private AbstractJsonContentAssert<?> post(String uri, String body) {
        MvcTestResult result = mvc.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatusOk();
        return assertThat(result).bodyJson();
    }

    @Test
    void searchKeepsHardFiltersExactAndLabelsTheKind() {
        var body = post("/api/search", """
                {"text": "trail running shoe", "filter": {"priceBelow": 120}}""");
        body.extractingPath("$.items[*].product.productId").asArray().containsExactly("B000000A1");
        body.extractingPath("$.items[0].product.lead.price").isEqualTo(99.99);
        body.extractingPath("$.items[0].product.maxPrice").isEqualTo(99.99);
        body.extractingPath("$.items[0].labels[0].constraint").isEqualTo("kind");
        body.extractingPath("$.items[0].labels[0].match").isEqualTo("MATCH");
        // Fewer than 10 products matched every word, so any word may match: the shorts come in, then the fit
        // check drops them as the wrong kind.
        body.extractingPath("$.totalProducts").isEqualTo(2);
        body.extractingPath("$.dropped").isEqualTo(1);
    }

    @Test
    void searchLeadsWithTheVariantThatMatchesColourAndSize() {
        var body = post("/api/search", """
                {"text": "running shorts", "color": "orange", "size": "small"}""");
        body.extractingPath("$.items[*].product.productId").asArray().containsExactly("B000000C1");
        body.extractingPath("$.items[0].product.lead.asin").isEqualTo("B000000C1");
        body.extractingPath("$.items[0].labels[*].match").asArray().containsExactly("MATCH", "MATCH", "MATCH");
        body.extractingPath("$.items[0].product.colors").asArray().containsExactly("Orange", "Orange/Black");
        body.extractingPath("$.items[0].photo").isEqualTo("NOT_CHECKED");
    }

    @Test
    void searchDropsWhatClearlyDoesNotFit() {
        var body = post("/api/search", """
                {"text": "trail running shoe", "color": "purple"}""");
        body.extractingPath("$.items").asArray().isEmpty();
        body.extractingPath("$.dropped").isEqualTo(2);
    }

    @Test
    void aMissingSizeIsUnknownAndCountedNeverAMatch() {
        var body = post("/api/search", """
                {"text": "hoodie", "size": "small"}""");
        body.extractingPath("$.items[0].product.productId").isEqualTo("B000000B1");
        body.extractingPath("$.items[0].labels[1].match").isEqualTo("UNKNOWN");
        body.extractingPath("$.items[0].labels[1].value").isNull();
        body.extractingPath("$.unknown.size").isEqualTo(1);
    }

    @Test
    void aMissingColourIsKeptOnlyWhenThePhotoShowsIt() {
        var body = post("/api/search", """
                {"text": "hoodie", "color": "red"}""");
        body.extractingPath("$.items").asArray().isEmpty();
        body.extractingPath("$.dropped").isEqualTo(1);
    }

    @Test
    void searchWithoutTextUsesOnlyTheFilters() {
        var body = post("/api/search", """
                {"filter": {"brand": "nike"}}""");
        // The hoodie has no store, and its brand falls back to Nike.
        body.extractingPath("$.items[*].product.productId")
                .asArray()
                .containsExactlyInAnyOrder("B000000B1", "B000000C1");
        body.extractingPath("$.items[0].labels").asArray().isEmpty();
    }

    @Test
    void similarProductsNeverIncludeTheProductItself() {
        MvcTestResult result =
                mvc.get().uri("/api/products/{id}/similar", "B000000A1").exchange();
        assertThat(result).hasStatusOk();
        assertThat(result)
                .bodyJson()
                .extractingPath("$.items[*].product.productId")
                .asArray()
                .doesNotContain("B000000A1");
        assertThat(mvc.get().uri("/api/products/{id}/similar", "B999999999").exchange())
                .bodyJson()
                .extractingPath("$.items")
                .asArray()
                .isEmpty();
    }

    @Test
    void chatRunsTheToolsAndReturnsWhatTheShopperSees() {
        model.script(toolCall("search_products", """
                        {"query": "running shorts", "colour": "orange", "size": "small", "maxPrice": 40}"""), say("Here are orange running shorts in small."));
        var body = post("/api/chat", """
                {"message": "I'm looking for orange running shorts in size small."}""");
        body.extractingPath("$.reply").isEqualTo("Here are orange running shorts in small.");
        body.extractingPath("$.products[*].product.productId").asArray().containsExactly("B000000C1");
        body.extractingPath("$.constraints.color").isEqualTo("orange");
        body.extractingPath("$.constraints.filter.priceBelow").isEqualTo(40.0);
        body.extractingPath("$.trace[0].tool").isEqualTo("search_products");
        body.extractingPath("$.trace[0].productIds").asArray().containsExactly("B000000C1");
        body.extractingPath("$.error").isNull();
    }

    @Test
    void followUpsSeeTheActiveSearchAndTheProductsShown() {
        model.script(toolCall("search_products", """
                        {"query": "trail running shoe", "maxPrice": 200}"""), say("Here is a trail shoe."));
        String conversation = conversationId(post("/api/chat", """
                {"message": "Trail running shoes under $200"}"""));
        model.script(say("Nothing cheaper."));
        post("/api/chat", """
                {"conversationId": "%s", "message": "Anything cheaper?"}""".formatted(conversation));
        String system = model.agentPrompts().get(0).getSystemMessage().getText();
        assertThat(system).contains("Current search:").contains("trail running shoe");
        assertThat(system).contains("1. id B000000A1").contains("lowest price $99.99");
    }

    @Test
    void chatOffersQuickRepliesAndLeavesTheGridAlone() {
        model.script(toolCall("suggest_replies", """
                        {"options": ["Women's shoes", "Men's shoes", " "]}"""), say("Who are the shoes for?"));
        var body = post("/api/chat", """
                {"message": "I need shoes"}""");
        body.extractingPath("$.quickReplies").asArray().containsExactly("Women's shoes", "Men's shoes");
        body.extractingPath("$.products").isNull();
    }

    @Test
    void chatAnswersCatalogQuestionsFromAQuery() {
        model.script(toolCall("catalog_stats", """
                {"brand": "NIKE"}"""), say("We carry 1 Nike product."));
        var body = post("/api/chat", """
                {"message": "How many Nike products do you have?"}""");
        body.extractingPath("$.trace[0].tool").isEqualTo("catalog_stats");
        ToolResponseMessage answer = (ToolResponseMessage)
                model.agentPrompts().get(1).getInstructions().getLast();
        assertThat(answer.getResponses().get(0).responseData())
                .contains("\"products\":2")
                .contains("\"listings\":3");
    }

    @Test
    void chatStopsCallingToolsAtTheLimit() {
        Object[] steps = new Object[8];
        for (int i = 0; i < 7; i++) {
            steps[i] = toolCall("get_product", """
                    {"id": "B000000A1"}""");
        }
        steps[7] = say("Here is what I found.");
        model.script(steps);
        var body = post("/api/chat", """
                {"message": "Tell me everything"}""");
        body.extractingPath("$.trace").asArray().hasSize(5);
        body.extractingPath("$.reply").isNotNull();
    }

    @Test
    void chatFailsSafelyWhenTheModelIsDown() {
        model.script(new IllegalStateException("Fireworks is down"));
        var body = post("/api/chat", """
                {"message": "Show me running shoes"}""");
        body.extractingPath("$.reply").isEqualTo("Sorry, the assistant can't answer right now. Please try again.");
        body.extractingPath("$.error").isEqualTo("IllegalStateException");
        body.extractingPath("$.products").asArray().isEmpty();
    }

    @Test
    void aSearchFromThePillsBecomesTheConversationsActiveSearch() {
        model.script(say("Hello."));
        String conversation = conversationId(post("/api/chat", """
                {"message": "Hi"}"""));
        post("/api/search?conversationId=" + conversation, """
                {"text": "hoodie"}""");
        model.script(say("Still hoodies."));
        post("/api/chat", """
                {"conversationId": "%s", "message": "And now?"}""".formatted(conversation));
        assertThat(model.agentPrompts().get(0).getSystemMessage().getText())
                .contains("hoodie")
                .contains("B000000B1");
    }

    @Test
    void blankMessagesAreRejected() {
        assertThat(mvc.post()
                        .uri("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \" \"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private static String conversationId(AbstractJsonContentAssert<?> body) {
        String[] id = new String[1];
        body.extractingPath("$.conversationId").asString().satisfies(value -> id[0] = value);
        return id[0];
    }
}
