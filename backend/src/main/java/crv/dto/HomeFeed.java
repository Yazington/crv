package crv.dto;

import java.util.List;

/**
 * The homepage's products.
 *
 * @param basedOn the clicked products the feed was built from, most recent first; empty when random
 */
public record HomeFeed(FeedBasis basis, List<String> basedOn, List<ResultCard> items) {

    public HomeFeed {
        basedOn = List.copyOf(basedOn);
        items = List.copyOf(items);
    }
}
