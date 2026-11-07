package crv.service;

import static crv.service.Variants.variant;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SharedPhotosTest {

    @Test
    void photoUsedByDifferentColoursIsSharedForEveryVariantUsingIt() {
        assertThat(SharedPhotos.flag(List.of(
                        variant("BLUE")
                                .color("Blue")
                                .mainPhoto("https://img/one.jpg")
                                .build(),
                        variant("ORANGE")
                                .color("Orange")
                                .mainPhoto("https://img/one.jpg")
                                .build(),
                        variant("UNKNOWN")
                                .color(null)
                                .mainPhoto("https://img/one.jpg")
                                .build(),
                        variant("RED")
                                .color("Red")
                                .mainPhoto("https://img/two.jpg")
                                .build())))
                .containsExactlyInAnyOrder("BLUE", "ORANGE", "UNKNOWN");
    }

    @Test
    void sameColourOrMissingColourIsNotADifferentColour() {
        assertThat(SharedPhotos.flag(List.of(
                        variant("S")
                                .color("Navy")
                                .size("S")
                                .mainPhoto("https://img/one.jpg")
                                .build(),
                        variant("M")
                                .color("navy")
                                .size("M")
                                .mainPhoto("https://img/one.jpg")
                                .build(),
                        variant("L")
                                .color(null)
                                .size("L")
                                .mainPhoto("https://img/one.jpg")
                                .build())))
                .isEmpty();
    }

    @Test
    void onlyMainPhotosCount() {
        assertThat(SharedPhotos.flag(List.of(
                        variant("BLUE").color("Blue").image(null).build(),
                        variant("ORANGE").color("Orange").image(null).build())))
                .isEmpty();
    }
}
