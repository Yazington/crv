package crv.service;

import crv.entity.ListPriceStatus;
import crv.entity.ProductEntity;
import crv.entity.VariantFields;
import crv.repository.CatalogWriteRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the raw catalog: clean, group, screen list prices, flag shared photos, score, then replace the stored
 * catalog. Plain code only: ingest never calls an LLM (requirements.md, AC13.6).
 */
@Service
public class IngestService {

    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final CatalogWriteRepository writer;

    public IngestService(JsonMapper jsonMapper, Clock clock, CatalogWriteRepository writer) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.writer = writer;
    }

    /** Replaces the catalog with the records in {@code file}, a JSON array of raw records. */
    @Transactional
    public IngestReport ingest(Path file) {
        RecordCleaner cleaner = new RecordCleaner(LocalDate.now(clock));
        List<VariantFields> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<SkipReason, Integer> skipped = new EnumMap<>(SkipReason.class);
        int records = 0;
        // Jackson 3 rejects tokens after a value by default; here the next record always follows.
        ObjectReader recordReader =
                jsonMapper.readerFor(JsonNode.class).without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (JsonParser parser = jsonMapper.createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("Expected a JSON array of records in " + file);
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode record = recordReader.readValue(parser);
                records++;
                switch (cleaner.clean(record)) {
                    case CleanOutcome.Kept(VariantFields fields) when seen.add(fields.asin()) -> kept.add(fields);
                    case CleanOutcome.Kept _ -> skipped.merge(SkipReason.DUPLICATE_ID, 1, Integer::sum);
                    case CleanOutcome.Skipped(SkipReason reason) -> skipped.merge(reason, 1, Integer::sum);
                }
            }
        }

        Map<String, ListPriceStatus> listPrices = ListPriceScreen.screen(kept);
        int maxReviews = kept.stream()
                .map(VariantFields::ratingCount)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        List<ProductEntity> products = new ArrayList<>();
        int sharedPhotoVariants = 0;
        for (ProductGroup group : VariantGrouper.group(kept)) {
            ProductEntity product =
                    new ProductEntity(group.productId(), group.variants().size());
            Set<String> sharedPhoto = SharedPhotos.flag(group.variants());
            sharedPhotoVariants += sharedPhoto.size();
            for (VariantFields fields : group.variants()) {
                product.addVariant(
                        fields,
                        listPrices.get(fields.asin()),
                        sharedPhoto.contains(fields.asin()),
                        ListingScores.quality(fields),
                        ListingScores.popularity(fields, maxReviews));
            }
            products.add(product);
        }
        writer.replaceAll(products);

        long suspect = listPrices.values().stream()
                .filter(status -> status == ListPriceStatus.SUSPECT)
                .count();
        return new IngestReport(
                records, kept.size(), skipped, products.size(), listPrices.size(), (int) suspect, sharedPhotoVariants);
    }
}
