package crv;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The homepage feed over HTTP, against the raw-format sample (see CatalogApiTest). */
@SpringBootTest(properties = "crv.ingest.file=" + CatalogApiTest.SAMPLE)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AssistantApiTest.Model.class})
class RecommendationApiTest {

    private final MockMvcTester mvc;

    RecommendationApiTest(@Autowired MockMvcTester mvc) {
        this.mvc = mvc;
    }

    private MvcTestResult click(String shopper, String product, String source) {
        return mvc.post()
                .uri("/api/interactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"shopperId": "%s", "productId": "%s", "source": "%s"}""".formatted(shopper, product, source))
                .exchange();
    }

    @Test
    void aNewShopperSeesRandomProductsEachOnce() {
        MvcTestResult result = mvc.get().uri("/api/home?shopperId=new-shopper").exchange();
        assertThat(result).hasStatusOk();
        var body = assertThat(result).bodyJson();
        body.extractingPath("$.basis").isEqualTo("RANDOM");
        body.extractingPath("$.basedOn").asArray().isEmpty();
        body.extractingPath("$.items[*].product.productId")
                .asArray()
                .containsExactlyInAnyOrder("B000000A1", "B000000B1", "B000000C1", "B000000G1");
        assertThat(mvc.get().uri("/api/home?size=2").exchange())
                .bodyJson()
                .extractingPath("$.items")
                .asArray()
                .hasSize(2);
    }

    @Test
    void clicksFromTheChatShapeTheNextHomepageWithoutRepeatingThem() {
        assertThat(click("shopper-1", "B000000C1", "CHAT")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(click("shopper-1", "B000000A1", "HOME")).hasStatus(HttpStatus.NO_CONTENT);

        var body = assertThat(mvc.get().uri("/api/home?shopperId=shopper-1").exchange())
                .bodyJson();
        body.extractingPath("$.basis").isEqualTo("HISTORY");
        body.extractingPath("$.basedOn").asArray().containsExactly("B000000A1", "B000000C1");
        body.extractingPath("$.items[*].product.productId")
                .asArray()
                .containsExactlyInAnyOrder("B000000B1", "B000000G1");
    }

    @Test
    void clicksOnUnknownProductsOrWithoutASourceAreRejected() {
        assertThat(click("shopper-2", "B999999999", "HOME")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(click("shopper-2", "B000000A1", "ELSEWHERE")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
