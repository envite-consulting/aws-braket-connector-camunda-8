package de.envite.connector.braket;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.outbound.OutboundConnectorFunction;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "de.envite.connector.braket",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    // -------------------------------------------------------------------------
    // 1. Layer dependency rules
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule connector_must_not_bypass_service_to_auth_provider =
            noClasses().that().haveSimpleName("BraketConnectorFunction")
                    .should().dependOnClassesThat().haveSimpleName("BraketAuthProvider")
                    .because("BraketConnectorFunction must delegate to BraketService, not call other classes directly");

    @ArchTest
    static final ArchRule connector_must_not_bypass_service_to_task_client =
            noClasses().that().haveSimpleName("BraketConnectorFunction")
                    .should().dependOnClassesThat().haveSimpleName("BraketTaskClient")
                    .because("BraketConnectorFunction must delegate to BraketService, not call other classes directly");

    @ArchTest
    static final ArchRule connector_must_not_bypass_service_to_parameter_handler =
            noClasses().that().haveSimpleName("BraketConnectorFunction")
                    .should().dependOnClassesThat().haveSimpleName("BraketParameterHandler")
                    .because("BraketConnectorFunction must delegate to BraketService, not call other classes directly");

    @ArchTest
    static final ArchRule dtos_must_not_depend_on_service_or_infrastructure =
            noClasses().that().resideInAPackage("..dto..")
                    .should().dependOnClassesThat().haveSimpleName("BraketService")
                    .orShould().dependOnClassesThat().haveSimpleName("BraketAuthProvider")
                    .orShould().dependOnClassesThat().haveSimpleName("BraketTaskClient")
                    .orShould().dependOnClassesThat().haveSimpleName("BraketParameterHandler")
                    .because("DTOs are plain data objects and must not depend on service or infrastructure classes");

    // -------------------------------------------------------------------------
    // 2. Naming convention rules
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule concrete_dto_classes_must_end_with_dto =
            classes().that().resideInAPackage("..dto..")
                    .and().areTopLevelClasses()
                    .and().doNotHaveModifier(JavaModifier.ABSTRACT)
                    .should().haveSimpleNameEndingWith("Dto")
                    .because("Concrete top-level classes in the dto package must end with 'Dto'");

    @ArchTest
    static final ArchRule dto_classes_must_start_with_braket =
            classes().that().resideInAPackage("..dto..")
                    .and().areTopLevelClasses()
                    .should().haveSimpleNameStartingWith("Braket")
                    .because("All top-level classes in the dto package must be prefixed with 'Braket'");

    @ArchTest
    static final ArchRule constants_classes_must_have_only_private_constructors =
            classes().that().haveSimpleNameEndingWith("Constants")
                    .should().haveOnlyPrivateConstructors()
                    .because("Constants classes are utility holders and must not be instantiated");

    // -------------------------------------------------------------------------
    // 3. Spring annotation rules
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule service_classes_must_be_annotated_with_service =
            classes().that().haveSimpleNameEndingWith("Service")
                    .should().beAnnotatedWith(Service.class)
                    .because("Classes ending with 'Service' must be annotated with @Service");

    @ArchTest
    static final ArchRule dtos_must_not_be_spring_beans =
            noClasses().that().resideInAPackage("..dto..")
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(Component.class)
                    .because("DTOs are plain data objects and must not be registered as Spring beans");

    @ArchTest
    static final ArchRule model_classes_must_not_be_spring_beans =
            noClasses().that().resideInAPackage("..model..")
                    .should().beAnnotatedWith(Service.class)
                    .orShould().beAnnotatedWith(Component.class)
                    .because("Domain model classes must not be registered as Spring beans");

    // -------------------------------------------------------------------------
    // 4. Camunda connector API rules
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule only_connector_function_implements_outbound_connector_function =
            classes().that().implement(OutboundConnectorFunction.class)
                    .should().haveSimpleNameEndingWith("ConnectorFunction")
                    .because("OutboundConnectorFunction must only be implemented by classes named '*ConnectorFunction'");

    @ArchTest
    static final ArchRule outbound_connector_annotation_requires_implementation =
            classes().that().areAnnotatedWith(OutboundConnector.class)
                    .should().implement(OutboundConnectorFunction.class)
                    .because("@OutboundConnector must only be placed on OutboundConnectorFunction implementations");

    // -------------------------------------------------------------------------
    // 5. No cyclic dependencies between sub-packages
    // -------------------------------------------------------------------------

    @ArchTest
    static final ArchRule no_cycles_between_subpackages =
            slices().matching("de.envite.connector.braket.(*)..")
                    .should().beFreeOfCycles();
}
