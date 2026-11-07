package crv.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** One catalog record: one colour, one size, its own parent_asin. */
@Entity
@Table(name = "variant")
public class VariantEntity {

    @Id
    private String asin;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private ProductEntity product;

    @Column(name = "product_id", insertable = false, updatable = false)
    private String productId;

    @Column(nullable = false)
    private String title;

    @Column
    private @Nullable String brand;

    @Column
    private @Nullable String color;

    @Column
    private @Nullable String size;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "list_price")
    private @Nullable BigDecimal listPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_price_status")
    private @Nullable ListPriceStatus listPriceStatus;

    @Column(name = "shared_photo", nullable = false)
    private boolean sharedPhoto;

    @Column(name = "average_rating")
    private @Nullable BigDecimal averageRating;

    @Column(name = "rating_count")
    private @Nullable Integer ratingCount;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> categories;

    @Column
    private @Nullable String department;

    @Column(name = "main_category")
    private @Nullable String mainCategory;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> features;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, String> specs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<ImageRef> images;

    @Column(name = "first_available")
    private @Nullable LocalDate firstAvailable;

    @Column(nullable = false)
    private double quality;

    @Column(nullable = false)
    private double popularity;

    protected VariantEntity() {}

    VariantEntity(
            ProductEntity product,
            VariantFields fields,
            @Nullable ListPriceStatus listPriceStatus,
            boolean sharedPhoto,
            double quality,
            double popularity) {
        this.asin = fields.asin();
        this.product = product;
        this.productId = product.getId();
        this.title = fields.title();
        this.brand = fields.brand();
        this.color = fields.color();
        this.size = fields.size();
        this.price = fields.price();
        this.listPrice = fields.listPrice();
        this.listPriceStatus = listPriceStatus;
        this.sharedPhoto = sharedPhoto;
        this.averageRating = fields.averageRating();
        this.ratingCount = fields.ratingCount();
        this.categories = fields.categories();
        this.department = fields.department();
        this.mainCategory = fields.mainCategory();
        this.features = fields.features();
        this.description = fields.description();
        this.details = fields.details();
        this.specs = fields.specs();
        this.images = fields.images();
        this.firstAvailable = fields.firstAvailable();
        this.quality = quality;
        this.popularity = popularity;
    }

    public String getAsin() {
        return asin;
    }

    public ProductEntity getProduct() {
        return product;
    }

    public String getProductId() {
        return productId;
    }

    public String getTitle() {
        return title;
    }

    public @Nullable String getBrand() {
        return brand;
    }

    public @Nullable String getColor() {
        return color;
    }

    public @Nullable String getSize() {
        return size;
    }

    public BigDecimal getPrice() {
        return price;
    }

    /** The list price, unless the screen marked it suspect. Suspect values are never shown or filtered on. */
    public @Nullable BigDecimal getTrustedListPrice() {
        return listPriceStatus == ListPriceStatus.TRUSTED ? listPrice : null;
    }

    /** True when the main photo is also the main photo of a differently coloured variant of the product. */
    public boolean isSharedPhoto() {
        return sharedPhoto;
    }

    public @Nullable BigDecimal getAverageRating() {
        return averageRating;
    }

    public @Nullable Integer getRatingCount() {
        return ratingCount;
    }

    public List<String> getCategories() {
        return List.copyOf(categories);
    }

    public @Nullable String getDepartment() {
        return department;
    }

    public @Nullable String getMainCategory() {
        return mainCategory;
    }

    public List<String> getFeatures() {
        return List.copyOf(features);
    }

    public List<String> getDescription() {
        return List.copyOf(description);
    }

    public Map<String, String> getDetails() {
        return Collections.unmodifiableMap(details);
    }

    public Map<String, String> getSpecs() {
        return Collections.unmodifiableMap(specs);
    }

    public List<ImageRef> getImages() {
        return List.copyOf(images);
    }

    public @Nullable LocalDate getFirstAvailable() {
        return firstAvailable;
    }

    public double getQuality() {
        return quality;
    }

    public double getPopularity() {
        return popularity;
    }
}
