package crv.service;

import static org.assertj.core.api.Assertions.assertThat;

import crv.entity.ImageRef;
import crv.entity.VariantFields;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RecordCleanerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RecordCleaner CLEANER = new RecordCleaner(LocalDate.of(2026, 11, 7));

    private static JsonNode json(String text) {
        return JSON.readTree(text.replace('\'', '"'));
    }

    private static VariantFields kept(String text) {
        CleanOutcome outcome = CLEANER.clean(json(text));
        assertThat(outcome).isInstanceOf(CleanOutcome.Kept.class);
        return ((CleanOutcome.Kept) outcome).fields();
    }

    @ParameterizedTest
    @ValueSource(strings = {"'nan'", "' NaN '", "'None'", "'null'", "''", "null"})
    void placeholdersBecomeNull(String value) {
        assertThat(RecordCleaner.text(json("{'v': " + value + "}").path("v"))).isNull();
    }

    @Test
    void textIsUnescapedAndTrimmed() {
        assertThat(RecordCleaner.text(
                        json("{'v': '  Tom &amp; Jerry &quot;Tee&quot; '}").path("v")))
                .isEqualTo("Tom & Jerry \"Tee\"");
        assertThat(RecordCleaner.text(json("{'v': 4.5}").path("v"))).isEqualTo("4.5");
        assertThat(RecordCleaner.text(json("{'v': ['a']}").path("v"))).isNull();
    }

    @Test
    void cleansEveryFieldByFormatOnly() {
        VariantFields fields = kept("""
                {'parent_asin': 'B01', 'title': ' Rain &amp; Wind Jacket ', 'store': 'nan', 'brand': 'Marmot',
                 'color': 'nan', 'size': ' M ', 'price': 56.16, 'original_price': '80', 'average_rating': 4.25,
                 'rating_number': 12, 'categories': ['Clothing, Shoes & Jewelry', 'nan', 'Men'],
                 'department': 'MENS', 'main_category': 'None', 'features': ['Light', 'nan', ' Packable '],
                 'description': ['One', ''], 'material': 'Nylon', 'style': 'nan',
                 'details': {'Weight': '1 lb', 'Blank': 'None', 'Rank': {'Jackets': 12, 'Rain': 'nan'}},
                 'images': [{'large': 'https://i/1.jpg', 'hi_res': null, 'thumb': 'https://i/1t.jpg', 'variant': 'MAIN'},
                            {'large': 'nan', 'hi_res': 'https://i/2.jpg'}],
                 'date_first_available': 1499126400000}
                """);
        assertThat(fields.asin()).isEqualTo("B01");
        assertThat(fields.title()).isEqualTo("Rain & Wind Jacket");
        assertThat(fields.store()).isNull();
        assertThat(fields.brand()).isEqualTo("Marmot");
        assertThat(fields.color()).isNull();
        assertThat(fields.size()).isEqualTo("M");
        assertThat(fields.price()).isEqualByComparingTo("56.16");
        assertThat(fields.price().scale()).isEqualTo(2);
        assertThat(fields.listPrice()).isEqualByComparingTo("80.00");
        assertThat(fields.averageRating()).isEqualByComparingTo("4.3");
        assertThat(fields.ratingCount()).isEqualTo(12);
        assertThat(fields.categories()).containsExactly("Clothing, Shoes & Jewelry", "Men");
        assertThat(fields.department()).isEqualTo("mens");
        assertThat(fields.mainCategory()).isNull();
        assertThat(fields.features()).containsExactly("Light", "Packable");
        assertThat(fields.description()).containsExactly("One");
        assertThat(fields.specs()).containsExactly(java.util.Map.entry("material", "Nylon"));
        assertThat(fields.details())
                .containsExactly(java.util.Map.entry("Weight", "1 lb"), java.util.Map.entry("Rank", "Jackets: 12"));
        assertThat(fields.images()).containsExactly(new ImageRef("https://i/1.jpg", null, "https://i/1t.jpg", "MAIN"));
        assertThat(fields.firstAvailable()).isEqualTo(LocalDate.of(2017, 7, 4));
    }

    @Test
    void storeIsTheBrandWhenPresent() {
        VariantFields fields =
                kept("{'parent_asin': 'B01', 'title': 'Tee', 'store': 'Acme', 'brand': 'Other', 'price': 5}");
        assertThat(fields.brand()).isEqualTo("Acme");
        assertThat(fields.store()).isEqualTo("Acme");
    }

    @Test
    void futureDatesAndOutOfRangeNumbersBecomeNull() {
        VariantFields fields = kept("""
                {'parent_asin': 'B01', 'title': 'Tee', 'price': '12.5', 'original_price': 0,
                 'average_rating': 7, 'rating_number': -3, 'date_first_available': 4102444800000}
                """);
        assertThat(fields.price()).isEqualByComparingTo("12.50");
        assertThat(fields.listPrice()).isNull();
        assertThat(fields.averageRating()).isNull();
        assertThat(fields.ratingCount()).isNull();
        assertThat(fields.firstAvailable()).isNull();
        assertThat(fields.categories()).isEmpty();
        assertThat(fields.details()).isEmpty();
        assertThat(fields.images()).isEmpty();
    }

    @Test
    void recordsWithoutIdTitleOrPriceAreSkipped() {
        assertThat(CLEANER.clean(json("{'parent_asin': 'nan', 'title': 'Tee', 'price': 5}")))
                .isEqualTo(new CleanOutcome.Skipped(SkipReason.NO_ID));
        assertThat(CLEANER.clean(json("{'parent_asin': 'B01', 'title': '  ', 'price': 5}")))
                .isEqualTo(new CleanOutcome.Skipped(SkipReason.NO_TITLE));
        assertThat(CLEANER.clean(json("{'parent_asin': 'B01', 'title': 'Tee', 'price': 'nan'}")))
                .isEqualTo(new CleanOutcome.Skipped(SkipReason.NO_PRICE));
        assertThat(CLEANER.clean(json("{'parent_asin': 'B01', 'title': 'Tee', 'price': 'cheap'}")))
                .isEqualTo(new CleanOutcome.Skipped(SkipReason.NO_PRICE));
        assertThat(CLEANER.clean(json("{'parent_asin': 'B01', 'title': 'Tee', 'price': -1}")))
                .isEqualTo(new CleanOutcome.Skipped(SkipReason.NO_PRICE));
    }

    @Test
    void ratingKeepsOneDecimal() {
        assertThat(kept("{'parent_asin': 'B01', 'title': 'Tee', 'price': 5, 'average_rating': 5.0}")
                        .averageRating())
                .isEqualTo(new BigDecimal("5.0"));
    }
}
