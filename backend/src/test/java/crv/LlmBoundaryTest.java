package crv;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** AC13.6: the LLM runs only at runtime. Ingest and cleaning never depend on it. */
@AnalyzeClasses(packages = "crv", importOptions = ImportOption.DoNotIncludeTests.class)
class LlmBoundaryTest {

    @ArchTest
    static final ArchRule INGEST_NEVER_CALLS_AN_LLM = noClasses()
            .that()
            .resideInAPackage("crv.cli..")
            .or()
            .haveSimpleNameStartingWith("Ingest")
            .or()
            .haveSimpleName("RecordCleaner")
            .or()
            .haveSimpleName("VariantGrouper")
            .or()
            .haveSimpleName("ListPriceScreen")
            .or()
            .haveSimpleName("ListingScores")
            .or()
            .haveSimpleName("SharedPhotos")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.ai..", "com.openai..")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Judge")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleName("PhotoCheck");
}
