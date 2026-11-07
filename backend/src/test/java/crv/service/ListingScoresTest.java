package crv.service;

import static crv.service.Variants.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class ListingScoresTest {

    @Test
    void completeListingPassesAllNineChecks() {
        var complete = variant("B1")
                .title("Merino Wool Hiking Socks")
                .features("Merino", "Cushioned", "Seamless toe")
                .description("x".repeat(201))
                .material("Merino wool")
                .image("https://img.example/hd.jpg")
                .image(null)
                .image(null)
                .rating("4.6", 340)
                .build();
        assertThat(ListingScores.quality(complete)).isEqualTo(1.0);
    }

    @Test
    void missingValuesFailTheirChecks() {
        var sparse = variant("B1").title("Tee").store(null).rating("3.0", 2).build();
        assertThat(ListingScores.quality(sparse)).isEqualTo(0.0);
        var borderline = variant("B2")
                .title("x".repeat(150))
                .description("x".repeat(200))
                .rating("4.0", 5)
                .build();
        // title, brand, 5+ reviews and 4.0 pass; the 200-character description does not.
        assertThat(ListingScores.quality(borderline)).isCloseTo(4.0 / 9, within(1e-9));
    }

    @Test
    void popularityIsRatingTimesDampenedReviewCount() {
        assertThat(ListingScores.popularity(variant("B1").rating("5.0", 40455).build(), 40455))
                .isCloseTo(1.0, within(1e-9));
        assertThat(ListingScores.popularity(variant("B2").rating("4.0", 400).build(), 40455))
                .isCloseTo(0.8 * Math.log1p(400) / Math.log1p(40455), within(1e-9));
        assertThat(ListingScores.popularity(variant("B3").build(), 40455)).isZero();
        assertThat(ListingScores.popularity(variant("B4").rating("4.0", 0).build(), 0))
                .isZero();
    }
}
