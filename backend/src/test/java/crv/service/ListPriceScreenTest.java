package crv.service;

import static crv.service.Variants.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import crv.entity.ListPriceStatus;
import crv.entity.VariantFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ListPriceScreenTest {

    private static final String[] HOODIES = {"Clothing, Shoes & Jewelry", "Men", "Hoodies"};

    /** Typical list prices from $40.00 to $59.20. */
    private static List<VariantFields> typical(int count, String... categories) {
        List<VariantFields> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(variant("T" + i)
                    .categories(categories)
                    .listPrice(String.valueOf(40 + i * 0.8))
                    .build());
        }
        return records;
    }

    @Test
    void onlyPricesFarBelowTheirCategoryAreSuspect() {
        List<VariantFields> records = typical(25, HOODIES);
        records.add(variant("CHEAP").categories(HOODIES).listPrice("0.01").build());
        records.add(variant("PRICEY").categories(HOODIES).listPrice("1763").build());
        records.add(variant("NORMAL").categories(HOODIES).listPrice("47").build());
        records.add(variant("NONE").categories(HOODIES).build());

        Map<String, ListPriceStatus> statuses = ListPriceScreen.screen(records);

        assertThat(statuses).containsEntry("CHEAP", ListPriceStatus.SUSPECT);
        // Far above is usually a real luxury item, so it stays trusted.
        assertThat(statuses).containsEntry("PRICEY", ListPriceStatus.TRUSTED);
        assertThat(statuses).containsEntry("NORMAL", ListPriceStatus.TRUSTED);
        assertThat(statuses).doesNotContainKey("NONE");
        assertThat(statuses).hasSize(28);
    }

    @Test
    void smallCategoriesUseTheirParent() {
        List<VariantFields> records = typical(25, HOODIES);
        String[] zipHoodies = {"Clothing, Shoes & Jewelry", "Men", "Hoodies", "Zip"};
        records.add(variant("ZIP_NORMAL").categories(zipHoodies).listPrice("52").build());
        records.add(variant("ZIP_ODD").categories(zipHoodies).listPrice("0.50").build());

        Map<String, ListPriceStatus> statuses = ListPriceScreen.screen(records);

        assertThat(statuses).containsEntry("ZIP_NORMAL", ListPriceStatus.TRUSTED);
        assertThat(statuses).containsEntry("ZIP_ODD", ListPriceStatus.SUSPECT);
    }

    @Test
    void spreadFloorKeepsNearIdenticalCategoriesFromFlaggingOrdinaryPrices() {
        List<VariantFields> records = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            records.add(variant("SAME" + i).categories(HOODIES).listPrice("20").build());
        }
        records.add(variant("A_BIT_LESS").categories(HOODIES).listPrice("16").build());

        assertThat(ListPriceScreen.screen(records)).containsEntry("A_BIT_LESS", ListPriceStatus.TRUSTED);
    }

    @Test
    void pricesWithoutEnoughPeersAreTrusted() {
        Map<String, ListPriceStatus> statuses = ListPriceScreen.screen(List.of(
                variant("ALONE").categories().listPrice("8369").build(),
                variant("FEW").categories(HOODIES).listPrice("0.01").build()));
        assertThat(statuses)
                .containsEntry("ALONE", ListPriceStatus.TRUSTED)
                .containsEntry("FEW", ListPriceStatus.TRUSTED);
    }

    @Test
    void medianOfOddAndEvenCounts() {
        assertThat(ListPriceScreen.median(List.of(3.0, 1.0, 2.0))).isEqualTo(2.0);
        assertThat(ListPriceScreen.median(List.of(4.0, 1.0, 2.0, 3.0))).isCloseTo(2.5, within(1e-9));
    }
}
