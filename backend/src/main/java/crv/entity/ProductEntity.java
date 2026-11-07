package crv.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The variants that belong together. Its id is the smallest variant asin in the group. */
@Entity
@Table(name = "product")
public class ProductEntity {

    @Id
    private String id;

    @Column(name = "variant_count", nullable = false)
    private int variantCount;

    @OneToMany(mappedBy = "product")
    @OrderBy("asin")
    private List<VariantEntity> variants = new ArrayList<>();

    protected ProductEntity() {}

    public ProductEntity(String id, int variantCount) {
        this.id = id;
        this.variantCount = variantCount;
    }

    public String getId() {
        return id;
    }

    public int getVariantCount() {
        return variantCount;
    }

    public List<VariantEntity> getVariants() {
        return Collections.unmodifiableList(variants);
    }

    /** Adds a variant to this product, linking both sides. */
    public VariantEntity addVariant(
            VariantFields fields,
            @Nullable ListPriceStatus listPriceStatus,
            boolean sharedPhoto,
            double quality,
            double popularity) {
        VariantEntity variant = new VariantEntity(this, fields, listPriceStatus, sharedPhoto, quality, popularity);
        variants.add(variant);
        return variant;
    }
}
