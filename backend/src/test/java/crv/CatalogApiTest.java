package crv;

import static org.assertj.core.api.Assertions.assertThat;

import crv.service.IngestReport;
import crv.service.IngestService;
import crv.service.SkipReason;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The catalog end to end: the ingest command loads a raw-format sample at startup into a real Postgres, then the
 * API is called over HTTP. The sample has 11 records: 7 variants in 4 products, plus 4 records that are skipped.
 * Two variants of one product share a main photo but differ in colour.
 */
@SpringBootTest(properties = "crv.ingest.file=" + CatalogApiTest.SAMPLE)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogApiTest {

    static final String SAMPLE = "src/test/resources/catalog-sample.json";

    private final MockMvcTester mvc;
    private final IngestService ingest;

    CatalogApiTest(@Autowired MockMvcTester mvc, @Autowired IngestService ingest) {
        this.mvc = mvc;
        this.ingest = ingest;
    }

    private AbstractJsonContentAssert<?> json(String uri, Object... variables) {
        MvcTestResult result = mvc.get().uri(uri, variables).exchange();
        assertThat(result).hasStatusOk();
        return assertThat(result).bodyJson();
    }

    private void assertBadRequest(String uri) {
        assertThat(mvc.get().uri(uri)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ingestReportsWhatItKeptAndSkippedAndCanRunAgain() {
        IngestReport report = ingest.ingest(Path.of(SAMPLE));

        assertThat(report)
                .isEqualTo(new IngestReport(
                        11,
                        7,
                        Map.of(
                                SkipReason.NO_ID, 1,
                                SkipReason.NO_TITLE, 1,
                                SkipReason.NO_PRICE, 1,
                                SkipReason.DUPLICATE_ID, 1),
                        4,
                        2,
                        0,
                        2));
        json("/api/catalog/stats").extractingPath("$.listings").isEqualTo(7);
    }

    @Test
    void listsOneCardPerProductMostPopularFirst() {
        var body = json("/api/products");
        body.extractingPath("$.totalProducts").isEqualTo(4);
        body.extractingPath("$.totalListings").isEqualTo(7);
        body.extractingPath("$.items[*].productId")
                .asArray()
                .containsExactly("B000000G1", "B000000A1", "B000000B1", "B000000C1");
    }

    @Test
    void sortsByPriceAndPages() {
        json("/api/products?sort=PRICE_ASC")
                .extractingPath("$.items[*].productId")
                .asArray()
                .containsExactly("B000000G1", "B000000C1", "B000000B1", "B000000A1");
        json("/api/products?sort=PRICE_DESC")
                .extractingPath("$.items[*].productId")
                .asArray()
                .containsExactly("B000000A1", "B000000B1", "B000000C1", "B000000G1");

        var secondPage = json("/api/products?sort=PRICE_ASC&page=1&size=3");
        secondPage.extractingPath("$.items[*].productId").asArray().containsExactly("B000000A1");
        secondPage.extractingPath("$.page").isEqualTo(1);
        secondPage.extractingPath("$.size").isEqualTo(3);
        secondPage.extractingPath("$.totalProducts").isEqualTo(4);
        json("/api/products?page=9").extractingPath("$.items").asArray().isEmpty();
    }

    @Test
    void cardShowsTheMatchingVariantsAndEveryColourAndSize() {
        var body = json("/api/products?priceBelow=120&categories={category}", "Trail Running");
        body.extractingPath("$.totalProducts").isEqualTo(1);
        body.extractingPath("$.totalListings").isEqualTo(1);
        body.extractingPath("$.items[0].productId").isEqualTo("B000000A1");
        body.extractingPath("$.items[0].lead.asin").isEqualTo("B000000A1");
        body.extractingPath("$.items[0].lead.brand").isEqualTo("Acme");
        body.extractingPath("$.items[0].lead.imageUrl").isEqualTo("https://img.example/a1.jpg");
        body.extractingPath("$.items[0].lead.averageRating").isEqualTo(4.5);
        body.extractingPath("$.items[0].lead.ratingCount").isEqualTo(120);
        body.extractingPath("$.items[0].minPrice").isEqualTo(99.99);
        body.extractingPath("$.items[0].maxPrice").isEqualTo(99.99);
        body.extractingPath("$.items[0].matchingVariantCount").isEqualTo(1);
        body.extractingPath("$.items[0].variantCount").isEqualTo(3);
        body.extractingPath("$.items[0].colors").asArray().containsExactly("Red", "Blue", "Green");
        body.extractingPath("$.items[0].sizes").asArray().containsExactly("8", "9", "10");
    }

    @Test
    void priceBoundsAreExact() {
        json("/api/products?priceAtMost=120&categories={category}", "Trail Running")
                .extractingPath("$.totalListings")
                .isEqualTo(2);
        json("/api/products?priceAtLeast=45.5")
                .extractingPath("$.totalListings")
                .isEqualTo(4);
        json("/api/products?priceAbove=45.5").extractingPath("$.totalListings").isEqualTo(3);

        var above100 = json("/api/products?priceAbove=100");
        above100.extractingPath("$.totalProducts").isEqualTo(1);
        // A2 and A3 tie on quality, so the cheaper one leads.
        above100.extractingPath("$.items[0].lead.asin").isEqualTo("B000000A2");
        above100.extractingPath("$.items[0].minPrice").isEqualTo(120.0);
        above100.extractingPath("$.items[0].maxPrice").isEqualTo(150.0);
    }

    @Test
    void brandMatchesIgnoringCaseAndFallsBackFromStore() {
        var body = json("/api/products?brand={brand}", " NiKe ");
        body.extractingPath("$.totalProducts").isEqualTo(2);
        body.extractingPath("$.totalListings").isEqualTo(3);
    }

    @Test
    void ratingCategoryAndListPriceFilters() {
        var rated = json("/api/products?minRating=4.5");
        rated.extractingPath("$.totalProducts").isEqualTo(2);
        rated.extractingPath("$.totalListings").isEqualTo(3);

        var categories = json("/api/products?categories=Wallets&categories={other}", "Active Shorts");
        categories.extractingPath("$.totalProducts").isEqualTo(2);
        categories.extractingPath("$.totalListings").isEqualTo(3);

        json("/api/products?listPriceAtMost=130")
                .extractingPath("$.totalListings")
                .isEqualTo(2);
        json("/api/products?listPriceBelow=130")
                .extractingPath("$.items[*].productId")
                .asArray()
                .containsExactly("B000000G1");
    }

    @Test
    void productDetailHasEveryCleanedField() {
        var body = json("/api/products/B000000A1");
        body.extractingPath("$.productId").isEqualTo("B000000A1");
        body.extractingPath("$.minPrice").isEqualTo(99.99);
        body.extractingPath("$.maxPrice").isEqualTo(150.0);
        body.extractingPath("$.variants[*].asin").asArray().containsExactly("B000000A1", "B000000A2", "B000000A3");
        // Every photo belongs to the product, once: A3 reuses A2's main photo.
        body.extractingPath("$.images[*].large")
                .asArray()
                .containsExactly(
                        "https://img.example/a1.jpg",
                        "https://img.example/a1-2.jpg",
                        "https://img.example/a1-3.jpg",
                        "https://img.example/a2.jpg");
        body.extractingPath("$.variants[*].sharedPhoto").asArray().containsExactly(false, true, true);

        body.extractingPath("$.variants[0].title").isEqualTo("Trail Runner Shoe");
        body.extractingPath("$.variants[0].listPrice").isEqualTo(130.0);
        body.extractingPath("$.variants[0].features")
                .asArray()
                .containsExactly("Mesh upper", "Rubber sole", "Lightweight");
        body.extractingPath("$.variants[0].details['Best Sellers Rank']")
                .isEqualTo("Clothing, Shoes & Jewelry: 1234; Trail Running Shoes: 5");
        body.extractingPath("$.variants[0].details").asMap().containsOnlyKeys("Item Weight", "Best Sellers Rank");
        body.extractingPath("$.variants[0].specs").asMap().containsExactly(Map.entry("material", "Mesh"));
        body.extractingPath("$.variants[0].department").isEqualTo("womens");
        body.extractingPath("$.variants[0].firstAvailable").isEqualTo("2017-07-04");
        body.extractingPath("$.variants[0].images").asArray().hasSize(3);
        body.extractingPath("$.variants[0].quality").isEqualTo(1.0);
        body.extractingPath("$.variants[1].listPrice").isNull();
    }

    @Test
    void missingValuesAreNullNeverPlaceholders() {
        MvcTestResult result = mvc.get().uri("/api/products/B000000B1").exchange();
        assertThat(result).hasStatusOk();
        assertThat(result).bodyText().doesNotContainIgnoringCase("\"nan\"", "\"none\"", "\"null\"", "&amp;");
        var body = assertThat(result).bodyJson();
        body.extractingPath("$.variants[0].title").isEqualTo("Classic Hoodie & Pocket");
        body.extractingPath("$.variants[0].brand").isEqualTo("Nike");
        body.extractingPath("$.variants[0].color").isNull();
        body.extractingPath("$.variants[0].size").isNull();
        body.extractingPath("$.variants[0].department").isNull();
        body.extractingPath("$.variants[0].firstAvailable").isNull();
    }

    @Test
    void unknownProductIsNotFound() {
        assertThat(mvc.get().uri("/api/products/B0NOTREAL")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void statsCountProductsListingsPricesAndBrands() {
        var all = json("/api/catalog/stats");
        all.extractingPath("$.products").isEqualTo(4);
        all.extractingPath("$.listings").isEqualTo(7);
        all.extractingPath("$.minPrice").isEqualTo(25.0);
        all.extractingPath("$.maxPrice").isEqualTo(150.0);
        all.extractingPath("$.brands[0].brand").isEqualTo("Acme");
        all.extractingPath("$.brands[0].products").isEqualTo(2);
        all.extractingPath("$.brands[0].listings").isEqualTo(4);
        all.extractingPath("$.brands[1].brand").asString().isEqualToIgnoringCase("nike");
        all.extractingPath("$.brands[1].products").isEqualTo(2);
        all.extractingPath("$.brands[1].listings").isEqualTo(3);

        var nike = json("/api/catalog/stats?brand=nike&brandLimit=1");
        nike.extractingPath("$.products").isEqualTo(2);
        nike.extractingPath("$.minPrice").isEqualTo(30.0);
        nike.extractingPath("$.maxPrice").isEqualTo(45.5);
        nike.extractingPath("$.brands").asArray().hasSize(1);

        var none = json("/api/catalog/stats?priceBelow=1");
        none.extractingPath("$.products").isEqualTo(0);
        none.extractingPath("$.minPrice").isNull();
        none.extractingPath("$.brands").asArray().isEmpty();
    }

    @Test
    void rejectsInvalidParameters() {
        assertBadRequest("/api/products?size=0");
        assertBadRequest("/api/products?size=101");
        assertBadRequest("/api/products?page=-1");
        assertBadRequest("/api/products?priceBelow=-1");
        assertBadRequest("/api/products?priceBelow=cheap");
        assertBadRequest("/api/products?minRating=6");
        assertBadRequest("/api/products?sort=RELEVANCE");
        assertBadRequest("/api/catalog/stats?brandLimit=0");
    }

    @Test
    void publishesTheOpenApiSpec() {
        json("/v3/api-docs")
                .extractingPath("$.paths")
                .asMap()
                .containsKeys("/api/products", "/api/products/{productId}", "/api/catalog/stats");
    }
}
