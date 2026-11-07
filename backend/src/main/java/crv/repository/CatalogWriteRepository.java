package crv.repository;

import crv.entity.ProductEntity;
import crv.entity.VariantEntity;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Bulk writes for the ingest. Callers run it inside a transaction, so a failed ingest keeps the old catalog. */
@Repository
public class CatalogWriteRepository {

    private static final int FLUSH_EVERY = 1000;

    private final EntityManager entityManager;

    public CatalogWriteRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Replaces the whole catalog with these products and their variants. */
    public void replaceAll(List<ProductEntity> products) {
        entityManager.createNativeQuery("truncate table variant, product").executeUpdate();
        int pending = 0;
        for (ProductEntity product : products) {
            entityManager.persist(product);
            for (VariantEntity variant : product.getVariants()) {
                entityManager.persist(variant);
            }
            pending += 1 + product.getVariants().size();
            if (pending >= FLUSH_EVERY) {
                entityManager.flush();
                entityManager.clear();
                pending = 0;
            }
        }
        entityManager.flush();
        entityManager.clear();
    }
}
