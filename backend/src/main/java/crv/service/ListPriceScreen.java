package crv.service;

import crv.entity.ListPriceStatus;
import crv.entity.VariantFields;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistical screen for implausible list prices (docs/data-and-search.md, "List price checks"). Each list price
 * is compared with the others in its category, using the modified z-score of log prices. A category with fewer
 * than {@value #MIN_GROUP} prices falls back to its parent. Only prices far below the typical value are suspect:
 * high outliers are mostly real luxury items, so they stay trusted.
 */
final class ListPriceScreen {

    static final int MIN_GROUP = 20;
    static final double Z_CUT = 3.5;
    /** Floor on the spread, in log units (about x1.35), so near-identical categories don't flag ordinary prices. */
    static final double MAD_FLOOR = 0.3;

    private static final double Z_SCALE = 0.6745;

    private ListPriceScreen() {}

    /** Status per asin, for every record with a list price. */
    static Map<String, ListPriceStatus> screen(List<VariantFields> records) {
        Map<List<String>, List<Double>> logPricesByNode = new HashMap<>();
        for (VariantFields r : records) {
            BigDecimal listPrice = r.listPrice();
            if (listPrice != null) {
                for (int depth = 1; depth <= r.categories().size(); depth++) {
                    logPricesByNode
                            .computeIfAbsent(List.copyOf(r.categories().subList(0, depth)), node -> new ArrayList<>())
                            .add(Math.log(listPrice.doubleValue()));
                }
            }
        }
        Map<List<String>, Spread> spreads = new HashMap<>();
        Map<String, ListPriceStatus> statuses = new HashMap<>();
        for (VariantFields r : records) {
            BigDecimal listPrice = r.listPrice();
            if (listPrice == null) {
                continue;
            }
            ListPriceStatus status = ListPriceStatus.TRUSTED;
            for (int depth = r.categories().size(); depth >= 1; depth--) {
                List<Double> logPrices =
                        logPricesByNode.getOrDefault(r.categories().subList(0, depth), List.of());
                if (logPrices.size() >= MIN_GROUP) {
                    Spread spread = spreads.computeIfAbsent(
                            List.copyOf(r.categories().subList(0, depth)), node -> Spread.of(logPrices));
                    double z = Z_SCALE * (Math.log(listPrice.doubleValue()) - spread.median()) / spread.mad();
                    if (z < -Z_CUT) {
                        status = ListPriceStatus.SUSPECT;
                    }
                    break;
                }
            }
            statuses.put(r.asin(), status);
        }
        return statuses;
    }

    private record Spread(double median, double mad) {

        static Spread of(List<Double> values) {
            double median = ListPriceScreen.median(values);
            List<Double> deviations = new ArrayList<>(values.size());
            for (double value : values) {
                deviations.add(Math.abs(value - median));
            }
            return new Spread(median, Math.max(ListPriceScreen.median(deviations), MAD_FLOOR));
        }
    }

    static double median(List<Double> values) {
        double[] sorted =
                values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
