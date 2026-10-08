package de.moritzf.quota

import com.tngtech.archunit.core.importer.ClassFileImporter
import de.moritzf.quota.idea.operations.ArchitectureOperationFixtures
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureRulesTest {
    @Test
    fun fullyQualifiedProviderDependencyCannotBypassBoundary() {
        val classes = ClassFileImporter().importClasses(FullyQualifiedProvider::class.java)
        val result = ArchitectureBoundariesTest.providerAndProxyBoundaries.evaluate(classes)
        assertTrue(result.hasViolation())
        assertTrue(result.failureReport.details.any { it.contains("QuotaSettingsState") })
    }

    @Test
    fun harmlessSourceTextDoesNotCreateDependency() {
        val classes = ClassFileImporter().importClasses(HarmlessProvider::class.java)
        assertFalse(
            ArchitectureBoundariesTest.providerAndProxyBoundaries.evaluate(classes).hasViolation()
        )
    }

    @Test
    fun operationAnnotationsAndAliasedWidgetTypesCannotBypassBoundary() {
        for (type in
            listOf(
                ArchitectureOperationFixtures.Annotated::class.java,
                ArchitectureOperationFixtures.Widget::class.java,
            )) {
            val classes = ClassFileImporter().importClasses(type)
            assertTrue(
                ArchitectureBoundariesTest.operationBoundaries.evaluate(classes).hasViolation(),
                type.name,
            )
        }
    }

    class FullyQualifiedProvider(val settings: de.moritzf.quota.idea.settings.QuotaSettingsState)

    class HarmlessProvider {
        fun text() = "de.moritzf.quota.idea.settings.QuotaSettingsState.getInstance()"
    }
}
