package crv.repository;

import crv.dto.CatalogFilter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Turns a filter into a SQL where clause over {@code variant v}, with named parameters. */
final class FilterSql {

    private final List<String> conditions = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();

    private FilterSql() {}

    static FilterSql of(CatalogFilter filter) {
        FilterSql sql = new FilterSql();
        sql.compare("v.price <", "priceBelow", filter.priceBelow());
        sql.compare("v.price <=", "priceAtMost", filter.priceAtMost());
        sql.compare("v.price >", "priceAbove", filter.priceAbove());
        sql.compare("v.price >=", "priceAtLeast", filter.priceAtLeast());
        sql.compare("v.average_rating >=", "minRating", filter.minRating());
        String brand = filter.brand();
        if (brand != null && !brand.isBlank()) {
            sql.conditions.add("lower(v.brand) = lower(:brand)");
            sql.params.put("brand", brand.strip());
        }
        List<String> categories = filter.categories();
        if (categories != null && !categories.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < categories.size(); i++) {
                names.add(":category" + i);
                sql.params.put("category" + i, categories.get(i));
            }
            sql.conditions.add("v.categories && array[" + String.join(", ", names) + "]::text[]");
        }
        sql.trustedListPrice("<", "listPriceBelow", filter.listPriceBelow());
        sql.trustedListPrice("<=", "listPriceAtMost", filter.listPriceAtMost());
        return sql;
    }

    FilterSql and(String condition) {
        conditions.add(condition);
        return this;
    }

    String where() {
        return conditions.isEmpty() ? "" : "where " + String.join(" and ", conditions);
    }

    Map<String, Object> params() {
        return params;
    }

    private void compare(String left, String name, @Nullable BigDecimal value) {
        if (value != null) {
            conditions.add(left + " :" + name);
            params.put(name, value);
        }
    }

    private void trustedListPrice(String operator, String name, @Nullable BigDecimal value) {
        if (value != null) {
            conditions.add("v.list_price_status = 'TRUSTED' and v.list_price " + operator + " :" + name);
            params.put(name, value);
        }
    }
}
