package crv;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/** The layer rules in docs/code-standards.md. */
@AnalyzeClasses(packages = "crv", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule LAYERS_CALL_ONE_WAY = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller")
            .definedBy("crv.controller..")
            .layer("Cli")
            .definedBy("crv.cli..")
            .layer("Service")
            .definedBy("crv.service..")
            .layer("Repository")
            .definedBy("crv.repository..")
            .whereLayer("Controller")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Cli")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Controller", "Cli")
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service");

    @ArchTest
    static final ArchRule ENTITIES_NEVER_REACH_THE_API = noClasses()
            .that()
            .resideInAnyPackage("crv.controller..", "crv.cli..", "crv.dto..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("crv.entity..");

    @ArchTest
    static final ArchRule TRANSACTIONAL_CLASSES_ARE_SERVICES =
            noClasses().that().resideOutsideOfPackage("crv.service..").should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule TRANSACTIONAL_METHODS_ARE_IN_SERVICES = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackage("crv.service..")
            .should()
            .beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule CONTROLLERS_ARE_NAMED_AND_PLACED = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .haveSimpleNameEndingWith("Controller")
            .andShould()
            .resideInAPackage("crv.controller..");

    @ArchTest
    static final ArchRule SERVICES_ARE_NAMED_AND_PLACED = classes()
            .that()
            .areAnnotatedWith(Service.class)
            .should()
            .haveSimpleNameEndingWith("Service")
            .andShould()
            .resideInAPackage("crv.service..");

    @ArchTest
    static final ArchRule REPOSITORIES_ARE_NAMED_AND_PLACED = classes()
            .that()
            .areAnnotatedWith(Repository.class)
            .or()
            .areAssignableTo(org.springframework.data.repository.Repository.class)
            .should()
            .haveSimpleNameEndingWith("Repository")
            .andShould()
            .resideInAPackage("crv.repository..");

    @ArchTest
    static final ArchRule NO_FIELD_INJECTION = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule NO_SYSTEM_OUT = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule NO_JAVA_UTIL_LOGGING = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;
}
