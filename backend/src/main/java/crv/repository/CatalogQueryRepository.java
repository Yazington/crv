package crv.repository;

import crv.dto.CatalogFilter;
import crv.dto.ProductSort;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Filtered reads over variants, grouped into products. Filtering and counting share one where clause. */
@Repository
public class CatalogQueryRepository {

    private final JdbcClient jdbc;

    public CatalogQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of products with at least one matching variant. */
    public List<ProductMatch> findMatches(CatalogFilter filter, ProductSort sort, long offset, int limit) {
        FilterSql filterSql = FilterSql.of(filter);
        String sql = """
                select product_id, min(price) as min_price, max(price) as max_price,
                       array_agg(asin order by asin) as asins
                from variant v
                %s
                group by product_id
                order by %s, product_id
                limit :limit offset :offset
                """.formatted(filterSql.where(), orderBy(sort));
        return jdbc.sql(sql)
                .params(filterSql.params())
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> new ProductMatch(
                        rs.getString("product_id"),
                        rs.getBigDecimal("min_price"),
                        rs.getBigDecimal("max_price"),
                        strings(rs.getArray("asins"))))
                .list();
    }

    public CatalogTotals totals(CatalogFilter filter) {
        FilterSql filterSql = FilterSql.of(filter);
        String sql = """
                select count(distinct product_id) as products, count(*) as listings,
                       min(price) as min_price, max(price) as max_price
                from variant v
                %s
                """.formatted(filterSql.where());
        return jdbc.sql(sql)
                .params(filterSql.params())
                .query((rs, row) -> new CatalogTotals(
                        rs.getLong("products"),
                        rs.getLong("listings"),
                        rs.getBigDecimal("min_price"),
                        rs.getBigDecimal("max_price")))
                .single();
    }

    /** Brands with the most matching products first. Brands that differ only in case count as one. */
    public List<BrandTotals> brandTotals(CatalogFilter filter, int limit) {
        FilterSql filterSql = FilterSql.of(filter).and("v.brand is not null");
        String sql = """
                select min(v.brand) as brand_name, count(distinct product_id) as products, count(*) as listings
                from variant v
                %s
                group by lower(v.brand)
                order by products desc, lower(v.brand)
                limit :limit
                """.formatted(filterSql.where());
        return jdbc.sql(sql)
                .params(filterSql.params())
                .param("limit", limit)
                .query((rs, row) ->
                        new BrandTotals(rs.getString("brand_name"), rs.getLong("products"), rs.getLong("listings")))
                .list();
    }

    private static String orderBy(ProductSort sort) {
        return switch (sort) {
            case POPULARITY -> "max(popularity) desc";
            case PRICE_ASC -> "min(price) asc";
            case PRICE_DESC -> "max(price) desc";
        };
    }

    private static List<String> strings(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
