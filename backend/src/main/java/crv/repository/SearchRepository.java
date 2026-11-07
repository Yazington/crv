package crv.repository;

import crv.dto.CatalogFilter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Full-text search over variants, inside the hard filters (docs/data-and-search.md, "Search flow"). Words in the
 * shopper's text must all match; when that finds too little, any word may match.
 */
@Repository
public class SearchRepository {

    /** All words must match: "running shoes" finds variants with both "run" and "shoe". */
    private static final String ALL_WORDS = "websearch_to_tsquery('english', :text)";

    /** Any word may match, ranked by how many do. */
    private static final String ANY_WORD = "replace(plainto_tsquery('english', :text)::text, ' & ', ' | ')::tsquery";

    private final JdbcClient jdbc;

    public SearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Variants that pass every filter and match the text, best first. Without text, every filtered variant matches
     * with relevance 0.
     *
     * @param anyWord match any word of the text instead of all of them
     */
    public List<VariantHit> hits(CatalogFilter filter, @Nullable String text, boolean anyWord, int limit) {
        String words = text == null ? "" : text.strip();
        boolean hasText = !words.isEmpty();
        FilterSql filterSql = FilterSql.of(filter);
        String relevance = "0.0";
        if (hasText) {
            String query = anyWord ? ANY_WORD : ALL_WORDS;
            filterSql.and("v.search_doc @@ " + query);
            relevance = "ts_rank_cd(v.search_doc, " + query + ")";
        }
        String sql = """
                select v.asin, v.product_id, %s as relevance, v.quality, v.popularity, v.color, v.size
                from variant v
                %s
                order by relevance desc, v.popularity desc, v.asin
                limit :limit
                """.formatted(relevance, filterSql.where());
        JdbcClient.StatementSpec statement =
                jdbc.sql(sql).params(filterSql.params()).param("limit", limit);
        if (hasText) {
            statement = statement.param("text", words);
        }
        return statement
                .query((rs, row) -> new VariantHit(
                        rs.getString("asin"),
                        rs.getString("product_id"),
                        rs.getDouble("relevance"),
                        rs.getDouble("quality"),
                        rs.getDouble("popularity"),
                        rs.getString("color"),
                        rs.getString("size")))
                .list();
    }

    /** Product and listing counts, price range and top brands for the variants that match the filters and text. */
    public SearchTotals totals(CatalogFilter filter, @Nullable String text, int brandLimit) {
        String words = text == null ? "" : text.strip();
        boolean hasText = !words.isEmpty();
        FilterSql filterSql = FilterSql.of(filter);
        if (hasText) {
            filterSql.and("v.search_doc @@ " + ALL_WORDS);
        }
        String where = filterSql.where();
        String totalsSql = """
                select count(distinct product_id) as products, count(*) as listings,
                       min(price) as min_price, max(price) as max_price
                from variant v
                %s
                """.formatted(where);
        String brandsSql = """
                select min(v.brand) as brand_name, count(distinct product_id) as products, count(*) as listings
                from variant v
                %s
                group by lower(v.brand)
                order by products desc, lower(v.brand)
                limit :limit
                """.formatted(filterSql.and("v.brand is not null").where());
        JdbcClient.StatementSpec totals = jdbc.sql(totalsSql).params(filterSql.params());
        JdbcClient.StatementSpec brands =
                jdbc.sql(brandsSql).params(filterSql.params()).param("limit", brandLimit);
        if (hasText) {
            totals = totals.param("text", words);
            brands = brands.param("text", words);
        }
        CatalogTotals counts = totals.query((rs, row) -> new CatalogTotals(
                        rs.getLong("products"),
                        rs.getLong("listings"),
                        rs.getBigDecimal("min_price"),
                        rs.getBigDecimal("max_price")))
                .single();
        List<BrandTotals> top = brands.query((rs, row) ->
                        new BrandTotals(rs.getString("brand_name"), rs.getLong("products"), rs.getLong("listings")))
                .list();
        return new SearchTotals(counts, top);
    }
}
