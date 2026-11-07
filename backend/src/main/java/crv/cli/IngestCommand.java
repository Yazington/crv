package crv.cli;

import crv.service.IngestReport;
import crv.service.IngestService;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the ingest at startup when {@code crv.ingest.file} is set. To ingest and exit:
 *
 * <pre>./gradlew bootRun --args='--crv.ingest.file=../data/raw/products.json --spring.main.web-application-type=none'</pre>
 */
@Component
@ConditionalOnProperty("crv.ingest.file")
public class IngestCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestCommand.class);

    private final IngestService ingest;
    private final Path file;

    public IngestCommand(IngestService ingest, @Value("${crv.ingest.file}") String file) {
        this.ingest = ingest;
        this.file = Path.of(file);
    }

    @Override
    public void run(ApplicationArguments args) {
        IngestReport report = ingest.ingest(file);
        log.info("Ingested {}: {}", file, report);
    }
}
