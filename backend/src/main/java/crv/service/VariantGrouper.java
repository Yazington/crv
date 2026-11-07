package crv.service;

import crv.entity.VariantFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Groups variants into products (docs/data-and-search.md, "Grouping variants into products"). Two records join
 * the same product when any signal matches:
 *
 * <ol>
 *   <li>the same title and store;
 *   <li>the same title and store after removing the record's own colour and size words;
 *   <li>the same store, rating and review count, and the same first four title words. Amazon shares one review
 *       count across the variants of a product.
 * </ol>
 */
final class VariantGrouper {

    private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile(" +");
    private static final char SEPARATOR = '\u0000';

    private VariantGrouper() {}

    static List<ProductGroup> group(List<VariantFields> records) {
        int[] parent = new int[records.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        link(records, parent, r -> join(norm(r.title()).strip(), norm(r.store())));
        link(records, parent, r -> join(withoutVariantWords(r), norm(r.store())));
        link(
                records,
                parent,
                r -> join(
                        norm(r.store()),
                        String.valueOf(r.averageRating()),
                        String.valueOf(r.ratingCount()),
                        String.join(
                                " ", words(norm(r.title())).stream().limit(4).toList())));

        Map<Integer, List<VariantFields>> byRoot = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            byRoot.computeIfAbsent(find(parent, i), root -> new ArrayList<>()).add(records.get(i));
        }
        Map<String, ProductGroup> byId = new TreeMap<>();
        for (List<VariantFields> variants : byRoot.values()) {
            variants.sort(Comparator.comparing(VariantFields::asin));
            String id = variants.getFirst().asin();
            byId.put(id, new ProductGroup(id, variants));
        }
        return List.copyOf(byId.values());
    }

    private static void link(List<VariantFields> records, int[] parent, Function<VariantFields, String> key) {
        Map<String, Integer> first = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            Integer earlier = first.putIfAbsent(key.apply(records.get(i)), i);
            if (earlier != null) {
                parent[find(parent, i)] = find(parent, earlier);
            }
        }
    }

    private static int find(int[] parent, int i) {
        int node = i;
        while (parent[node] != node) {
            parent[node] = parent[parent[node]];
            node = parent[node];
        }
        return node;
    }

    /** Lowercase, with every run of characters other than a-z, 0-9 and space turned into one space. */
    static String norm(@Nullable String value) {
        return value == null
                ? ""
                : NOT_WORD.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private static String withoutVariantWords(VariantFields r) {
        String title = " " + norm(r.title()) + " ";
        for (String variantValue : new String[] {r.color(), r.size()}) {
            String words = norm(variantValue).strip();
            if (words.length() > 1) {
                title = title.replace(" " + words + " ", " ");
            }
        }
        return String.join(" ", words(title));
    }

    private static List<String> words(String normalized) {
        String trimmed = normalized.strip();
        return trimmed.isEmpty() ? List.of() : List.of(SPACES.split(trimmed));
    }

    private static String join(String... parts) {
        return String.join(String.valueOf(SEPARATOR), parts);
    }
}
