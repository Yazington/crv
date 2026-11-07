package crv.repository;

import crv.entity.VariantEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariantRepository extends JpaRepository<VariantEntity, String> {

    List<VariantEntity> findByProductIdInOrderByAsin(Collection<String> productIds);
}
