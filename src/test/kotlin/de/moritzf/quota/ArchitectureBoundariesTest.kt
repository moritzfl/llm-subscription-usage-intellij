package de.moritzf.quota

import com.intellij.concurrency.virtualThreads.IntelliJVirtualThreads
import com.intellij.mcpserver.McpToolset
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.Dependency
import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.junit.LocationProvider
import com.tngtech.archunit.lang.conditions.ArchConditions.onlyHaveDependenciesWhere
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import de.moritzf.proxy.logging.RequestLogger
import de.moritzf.proxy.server.ApiKeyStore
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.mcp.SubscriptionUsageMcpToolset
import de.moritzf.quota.shared.RealtimeSpeechSession
import java.net.URI

@AnalyzeClasses(locations = [ArchitectureBoundariesTest.ProductionClasses::class])
class ArchitectureBoundariesTest {
    /** Use the production artifact, including in IntelliJ's instrumented Gradle test sandbox. */
    class ProductionClasses : LocationProvider {
        override fun get(testClass: Class<*>): Set<Location> {
            // IntelliJ's PathClassLoader supplies resource URLs but no CodeSource location.
            val anchor = QuotaProviderType::class.java
            val classFile = anchor.name.replace('.', '/') + ".class"
            val resource = checkNotNull(anchor.getResource("/$classFile")).toExternalForm()
            check(resource.endsWith(classFile))
            return setOf(Location.of(URI.create(resource.removeSuffix(classFile))))
        }
    }

    @ArchTest
    fun productionImportExcludesTestFixtures(classes: JavaClasses) {
        check(classes.contain(QuotaProviderType::class.java))
        check(classes.contain(RealtimeSpeechSession::class.java))
        check(classes.contain("de.moritzf.quota.idea.operations.SubscriptionSearchOperationsKt"))
        check(!classes.contain(ArchitectureBoundariesTest::class.java))
    }

    companion object {
        @ArchTest
        @JvmField
        val sharedBoundaries =
            classes()
                .that()
                .resideInAPackage("de.moritzf.quota.shared..")
                .should(
                    onlyHaveDependenciesWhere(
                        DescribedPredicate.describe<Dependency>(
                            "use only shared code or external libraries"
                        ) { dependency ->
                            !resideInAnyPackage("de.moritzf..").test(dependency.targetClass) ||
                                resideInAnyPackage("de.moritzf.quota.shared..")
                                    .test(dependency.targetClass)
                        }
                    )
                )
                .because(
                    "shared helpers must not depend on provider, proxy, or IDE implementations"
                )

        // Exact runtime bindings. Including
        // nested classes covers Kotlin companions without exempting whole provider/IDE packages.
        private val allowedIdeDependencies =
            listOf(
                    ApiKeyStore::class.java to IntelliJVirtualThreads::class.java,
                    RequestLogger::class.java to IntelliJVirtualThreads::class.java,
                )
                .map { (origin, target) ->
                    Dependency.Predicates.dependencyOrigin(belongToAnyOf(origin))
                        .and(Dependency.Predicates.dependencyTarget(belongToAnyOf(target)))
                }

        @ArchTest
        @JvmField
        val providerAndProxyBoundaries =
            classes()
                .that()
                .resideOutsideOfPackage("de.moritzf.quota.idea..")
                .should(
                    onlyHaveDependenciesWhere(
                        DescribedPredicate.describe<Dependency>(
                            "stay outside IDE code except documented class pairs"
                        ) { dependency ->
                            !resideInAnyPackage("com.intellij..", "de.moritzf.quota.idea..")
                                .test(dependency.targetClass) ||
                                allowedIdeDependencies.any { it.test(dependency) }
                        }
                    )
                )
                .because(
                    "provider clients and proxy transport must stay independent of IDE orchestration"
                )

        @ArchTest
        @JvmField
        val operationBoundaries =
            noClasses()
                .that()
                .resideInAPackage("de.moritzf.quota.idea.operations..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                    "com.intellij.mcpserver.annotations..",
                    "de.moritzf.quota.idea.ui..",
                    "javax.swing..",
                )
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(McpToolset::class.java)
                .orShould()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("SettingsPanel")
                .orShould()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("SettingsConfigurable")
                .because(
                    "operations execute capabilities; MCP annotations and settings widgets belong at the boundary"
                )

        @ArchTest
        @JvmField
        val facadeDelegatesServiceLookup =
            noClasses()
                .that(belongToAnyOf(SubscriptionUsageMcpToolset::class.java))
                .should()
                .callMethodWhere(
                    DescribedPredicate.describe<JavaMethodCall>("look up an IDE service") { call ->
                        call.target.name == "getInstance" &&
                            resideInAnyPackage("de.moritzf.quota.idea..", "com.intellij..")
                                .test(call.target.owner)
                    }
                )
                .because("the subscription MCP facade must delegate service lookup to operations")
    }
}
