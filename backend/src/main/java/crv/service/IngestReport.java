package crv.service;

import java.util.Map;

/**
 * What one ingest did.
 *
 * @param records raw records read
 * @param variants records kept
 * @param skipped records left out, by reason
 * @param products products the variants were grouped into
 * @param listPrices variants with a list price
 * @param suspectListPrices list prices far below their category's typical price, never shown or filtered on
 * @param sharedPhotoVariants variants whose main photo is also the main photo of a differently coloured variant
 */
public record IngestReport(
        int records,
        int variants,
        Map<SkipReason, Integer> skipped,
        int products,
        int listPrices,
        int suspectListPrices,
        int sharedPhotoVariants) {

    public IngestReport {
        skipped = Map.copyOf(skipped);
    }
}
