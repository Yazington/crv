package crv.service;

import crv.entity.ImageRef;
import crv.entity.VariantFields;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

/** Quality and popularity (docs/data-and-search.md, "Ranking: quality and popularity"). */
final class ListingScores {

    private static final BigDecimal GOOD_RATING = new BigDecimal("4.0");

    /** The 9 checks. A missing value fails its check. */
    private static final List<Predicate<VariantFields>> QUALITY_CHECKS = List.of(
            r -> between(length(r.title()), 10, 150),
            r -> r.features().size() >= 3,
            r -> length(String.join(" ", r.description())) > 200,
            r -> r.brand() != null,
            r -> r.specs().containsKey("material"),
            r -> r.images().size() >= 3,
            r -> r.images().stream().map(ImageRef::hiRes).anyMatch(hiRes -> hiRes != null),
            r -> r.ratingCount() != null && r.ratingCount() >= 5,
            r -> r.averageRating() != null && r.averageRating().compareTo(GOOD_RATING) >= 0);

    private ListingScores() {}

    /** The share of quality checks the listing passes, from 0 to 1. */
    static double quality(VariantFields r) {
        long passed = QUALITY_CHECKS.stream().filter(check -> check.test(r)).count();
        return (double) passed / QUALITY_CHECKS.size();
    }

    /**
     * Rating times a dampened review count, from 0 to 1: {@code (rating / 5) × log(1 + reviews) / log(1 +
     * maxReviews)}, where maxReviews is the largest review count in the catalog.
     */
    static double popularity(VariantFields r, int maxReviews) {
        BigDecimal rating = r.averageRating();
        Integer reviews = r.ratingCount();
        if (rating == null || reviews == null || maxReviews <= 0) {
            return 0;
        }
        return rating.doubleValue() / 5 * Math.log1p(reviews) / Math.log1p(maxReviews);
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }
}
