package crv.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Shopper clicks, and the product picks the homepage is built from. */
@Repository
public class InteractionRepository {

    private final JdbcClient jdbc;

    public InteractionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String shopperId, String productId, String source) {
        jdbc.sql("insert into interaction (shopper_id, product_id, source) values (:shopper, :product, :source)")
                .param("shopper", shopperId)
                .param("product", productId)
                .param("source", source)
                .update();
    }

    /** The products a shopper clicked, most recent first, each once. */
    public List<String> recentProducts(String shopperId, int limit) {
        return jdbc.sql("""
                        select product_id from interaction
                        where shopper_id = :shopper
                        group by product_id
                        order by max(created_at) desc, product_id
                        limit :limit
                        """)
                .param("shopper", shopperId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public boolean productExists(String productId) {
        return jdbc.sql("select exists (select 1 from product where id = :id)")
                .param("id", productId)
                .query(Boolean.class)
                .single();
    }

    /** Random products. Complete listings are more likely: the draw is weighted by the best listing's quality. */
    public List<String> randomProducts(int limit) {
        return jdbc.sql("""
                        select product_id from variant
                        group by product_id
                        order by random() * (0.5 + max(quality)) desc
                        limit :limit
                        """).param("limit", limit).query(String.class).list();
    }
}
