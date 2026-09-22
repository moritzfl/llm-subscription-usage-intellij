package de.moritzf.quota.idea.settings

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import de.moritzf.quota.idea.common.ProviderSnapshot
import de.moritzf.quota.openai.OpenAiCodexQuota
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QuotaRefreshButtonTest {
    @Test
    fun spinsUntilCompletionThenShowsCheckmarkAndResets() {
        val request = CompletableFuture<ProviderSnapshot?>()
        val requestedAccounts = mutableListOf<String>()
        val shownIcons = mutableListOf<Icon>()
        val reset = CountDownLatch(1)
        var completedOnEdt = false
        val button = onEdt {
            QuotaRefreshButton(
                refresh = { accountId ->
                    requestedAccounts += accountId
                    request
                },
                onCompleted = { completedOnEdt = SwingUtilities.isEventDispatchThread() },
                feedbackMillis = 50,
            ).apply {
                accountId = "personal"
                addPropertyChangeListener("icon") { event ->
                    shownIcons += event.newValue as Icon
                    if (event.newValue === AllIcons.Actions.Refresh) reset.countDown()
                }
            }
        }
        try {
            onEdt {
                button.doClick(0)
                assertIs<AnimatedIcon>(button.icon)
                assertSame(button.icon, button.disabledIcon)
                assertFalse(button.isEnabled)
                assertEquals("Refreshing quota...", button.toolTipText)
                button.doClick(0)
                assertEquals(listOf("personal"), requestedAccounts)
                assertFalse(completedOnEdt)
            }

            request.complete(ProviderSnapshot(OpenAiCodexQuota(), null))
            assertTrue(reset.await(5, TimeUnit.SECONDS), "Success feedback did not reset")
            onEdt {
                assertEquals(listOf(AllIcons.Actions.Checked, AllIcons.Actions.Refresh), shownIcons.drop(1))
                assertTrue(completedOnEdt)
                assertTrue(button.isEnabled)
                assertEquals("Refresh quota", button.toolTipText)
            }
        } finally {
            onEdt { button.removeNotify() }
        }
    }

    @Test
    fun showsFailureForErrorsMissingDataAndExceptionalCompletion() {
        val results = listOf<ProviderSnapshot?>(
            ProviderSnapshot(OpenAiCodexQuota(), "Refresh failed; cached quota retained"),
            ProviderSnapshot(null, "Not logged in"),
            ProviderSnapshot(null, null),
            null,
        )
        val requests = results.map { CompletableFuture.completedFuture(it) } +
            CompletableFuture.failedFuture<ProviderSnapshot?>(IllegalStateException("Refresh failed"))
        requests.forEach { request ->
            var completions = 0
            val button = onEdt {
                QuotaRefreshButton({ request }, { completions++ }, feedbackMillis = 60_000).apply {
                    accountId = "work"
                    doClick(0)
                }
            }
            try {
                onEdt {
                    assertSame(AllIcons.Actions.Cancel, button.icon)
                    assertEquals("Quota refresh failed", button.toolTipText)
                    assertEquals(button.toolTipText, button.accessibleContext.accessibleName)
                    assertTrue(button.isEnabled)
                    assertEquals(1, completions)
                }
            } finally {
                onEdt { button.removeNotify() }
            }
        }
    }

    @Test
    fun switchingAccountsIgnoresPreviousRefreshCompletion() {
        val work = CompletableFuture<ProviderSnapshot?>()
        val personal = CompletableFuture<ProviderSnapshot?>()
        var completions = 0
        val button = onEdt {
            QuotaRefreshButton(
                refresh = { if (it == "work") work else personal },
                onCompleted = { completions++ },
                feedbackMillis = 60_000,
            ).apply {
                accountId = "work"
                doClick(0)
                accountId = "personal"
                assertSame(AllIcons.Actions.Refresh, icon)
                assertTrue(isEnabled)
                doClick(0)
            }
        }
        try {
            work.complete(ProviderSnapshot(OpenAiCodexQuota(), null))
            onEdt {
                assertIs<AnimatedIcon>(button.icon)
                assertFalse(button.isEnabled)
                assertEquals(0, completions)
            }
            personal.complete(ProviderSnapshot(OpenAiCodexQuota(), null))
            onEdt {
                assertSame(AllIcons.Actions.Checked, button.icon)
                assertTrue(button.isEnabled)
                assertEquals(1, completions)
            }
        } finally {
            onEdt { button.removeNotify() }
        }
    }

    @Test
    fun removingButtonIgnoresPendingCompletion() {
        val request = CompletableFuture<ProviderSnapshot?>()
        var completions = 0
        val button = onEdt {
            QuotaRefreshButton({ request }, { completions++ }).apply {
                accountId = "work"
                doClick(0)
                removeNotify()
            }
        }

        request.complete(ProviderSnapshot(OpenAiCodexQuota(), null))
        onEdt {
            assertSame(AllIcons.Actions.Refresh, button.icon)
            assertEquals(0, completions)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
