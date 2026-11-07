package crv.service;

import static crv.service.Variants.variant;
import static org.assertj.core.api.Assertions.assertThat;

import crv.entity.VariantFields;
import java.util.List;
import org.junit.jupiter.api.Test;

class VariantGrouperTest {

    private static List<List<String>> groups(VariantFields... records) {
        return VariantGrouper.group(List.of(records)).stream()
                .map(group -> group.variants().stream().map(VariantFields::asin).toList())
                .toList();
    }

    @Test
    void sameTitleAndStoreIsOneProductNamedByItsSmallestAsin() {
        List<ProductGroup> products = VariantGrouper.group(List.of(
                variant("B3").title("Running Shorts").color("Red").build(),
                variant("B1").title("running shorts!").color("Blue").build()));
        assertThat(products).hasSize(1);
        assertThat(products.getFirst().productId()).isEqualTo("B1");
        assertThat(products.getFirst().variants())
                .extracting(VariantFields::asin)
                .containsExactly("B1", "B3");
    }

    @Test
    void colourAndSizeWordsAreIgnoredInTheTitle() {
        assertThat(groups(
                        variant("B1")
                                .title("Trail Shoe Green")
                                .color("Green")
                                .size("10")
                                .build(),
                        variant("B2")
                                .title("Trail Shoe X-Large")
                                .color("Red")
                                .size("X-Large")
                                .build(),
                        variant("B3").title("Trail Shoe").color("Blue").build()))
                .containsExactly(List.of("B1", "B2", "B3"));
    }

    @Test
    void sharedReviewSignatureAndFirstFourWordsLink() {
        assertThat(groups(
                        variant("B1")
                                .title("Nike Dri Fit Shorts Orange")
                                .rating("3.9", 4)
                                .build(),
                        variant("B2")
                                .title("Nike Dri Fit Shorts For Running")
                                .rating("3.9", 4)
                                .build(),
                        variant("B3")
                                .title("Nike Dri Fit Shorts Plus")
                                .rating("4.1", 4)
                                .build()))
                .containsExactly(List.of("B1", "B2"), List.of("B3"));
    }

    @Test
    void differentStoresStayApart() {
        assertThat(groups(
                        variant("B1").title("Plain Tee").store("Acme").build(),
                        variant("B2").title("Plain Tee").store("Other").build(),
                        variant("B3").title("Plain Tee").store(null).build()))
                .containsExactly(List.of("B1"), List.of("B2"), List.of("B3"));
    }

    @Test
    void signalsLinkTransitively() {
        // B1-B2 share a title; B2-B3 share a review signature and first four words; B1-B3 share neither.
        assertThat(groups(
                        variant("B1")
                                .title("Wool Hiking Socks Pack")
                                .rating("4.2", 7)
                                .build(),
                        variant("B2")
                                .title("Wool Hiking Socks Pack")
                                .rating("4.6", 30)
                                .build(),
                        variant("B3")
                                .title("Wool Hiking Socks Pack Of Three")
                                .rating("4.6", 30)
                                .build()))
                .containsExactly(List.of("B1", "B2", "B3"));
    }

    @Test
    void normKeepsOnlyLettersDigitsAndSpaces() {
        assertThat(VariantGrouper.norm("Men's T-Shirt (2-Pack) ÉTÉ")).isEqualTo("men s t shirt  2 pack   t ");
        assertThat(VariantGrouper.norm(null)).isEmpty();
    }
}
