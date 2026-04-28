package de.moritzf.quota.idea.ui.popup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.ollama.OllamaQuota
import kotlinx.datetime.Instant
import java.awt.Component
import java.awt.Point
import javax.swing.JComponent

internal enum class QuotaPopupLocation {
    ABOVE,
    BELOW,
}

internal object QuotaPopupSupport {
    fun showPopup(
        project: Project,
        component: Component,
        quota: OpenAiCodexQuota?,
        error: String?,
        openCodeQuota: OpenCodeQuota?,
        openCodeError: String?,
        ollamaQuota: OllamaQuota?,
        ollamaError: String?,
        location: QuotaPopupLocation,
    ) {
        if (project.isDisposed) {
            return
        }

        QuotaUsageService.getInstance().refreshNowAsync()
        var popup: JBPopup? = null
        val content = RefreshablePopupPanel<QuotaPopupContentState> { state ->
            buildPopupContent(project, component, state.quota, state.error, state.openCodeQuota, state.openCodeError, state.ollamaQuota, state.ollamaError) { popup?.cancel() }
        }.apply {
            refresh(QuotaPopupContentState(quota, error, openCodeQuota, openCodeError, ollamaQuota, ollamaError))
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()

        val currentPopup = popup
        val popupConnection: MessageBusConnection = ApplicationManager.getApplication().messageBus.connect(currentPopup)
        var latestQuota = quota
        var latestError = error
        var latestOpenCodeQuota = openCodeQuota
        var latestOpenCodeError = openCodeError
        var latestOllamaQuota = ollamaQuota
        var latestOllamaError = ollamaError
        var refreshScheduled = false
        fun scheduleRefresh() {
            if (refreshScheduled) {
                return
            }
            refreshScheduled = true
            ApplicationManager.getApplication().invokeLater {
                refreshScheduled = false
                refreshPopup(currentPopup, content, component, location, latestQuota, latestError, latestOpenCodeQuota, latestOpenCodeError, latestOllamaQuota, latestOllamaError)
            }
        }
        popupConnection.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?) {
                latestQuota = quota
                latestError = error
                scheduleRefresh()
            }

            override fun onOpenCodeQuotaUpdated(quota: OpenCodeQuota?, error: String?) {
                latestOpenCodeQuota = quota
                latestOpenCodeError = error
                scheduleRefresh()
            }

            override fun onOllamaQuotaUpdated(quota: OllamaQuota?, error: String?) {
                latestOllamaQuota = quota
                latestOllamaError = error
                scheduleRefresh()
            }
        })

        popup.show(RelativePoint(component, popupPoint(component, content, location)))
    }

    private fun refreshPopup(
        currentPopup: JBPopup,
        content: RefreshablePopupPanel<QuotaPopupContentState>,
        component: Component,
        location: QuotaPopupLocation,
        quota: OpenAiCodexQuota?,
        error: String?,
        openCodeQuota: OpenCodeQuota?,
        openCodeError: String?,
        ollamaQuota: OllamaQuota?,
        ollamaError: String?,
    ) {
        if (currentPopup.isDisposed || !currentPopup.isVisible) {
            return
        }
        val oldSize = content.preferredSize
        content.refresh(QuotaPopupContentState(quota, error, openCodeQuota, openCodeError, ollamaQuota, ollamaError))
        val newSize = content.preferredSize
        if (oldSize != newSize) {
            currentPopup.pack(true, true)
            val newPoint = popupPoint(component, content, location)
            val screenPoint = RelativePoint(component, newPoint).getScreenPoint()
            currentPopup.setLocation(screenPoint)
            currentPopup.moveToFitScreen()
        }
    }

    private fun popupPoint(component: Component, content: JComponent, location: QuotaPopupLocation): Point {
        val popupSize = content.preferredSize
        val x = (component.width - popupSize.width) / 2
        val gap = JBUI.scale(4)
        val y = when (location) {
            QuotaPopupLocation.ABOVE -> -popupSize.height - gap
            QuotaPopupLocation.BELOW -> component.height + gap
        }
        return Point(x, y)
    }

    private fun buildPopupContent(
        project: Project,
        component: Component,
        currentQuota: OpenAiCodexQuota?,
        currentError: String?,
        openCodeQuota: OpenCodeQuota?,
        openCodeError: String?,
        ollamaQuota: OllamaQuota?,
        ollamaError: String?,
        onClosePopup: () -> Unit,
    ): JComponent {
        val settings = QuotaSettingsState.getInstance()
        val hideOpenAi = settings.hideOpenAiFromQuotaPopup
        val hideOpenCode = settings.hideOpenCodeFromQuotaPopup
        val hideOllama = settings.hideOllamaFromQuotaPopup
        val hasReviewData = currentQuota != null && (
            currentQuota.reviewPrimary != null || currentQuota.reviewSecondary != null ||
                currentQuota.reviewAllowed != null || currentQuota.reviewLimitReached != null
            )

        val authService = QuotaAuthService.getInstance()
        val openCodeCookieStore = OpenCodeSessionCookieStore.getInstance()
        val ollamaCookieStore = OllamaSessionCookieStore.getInstance()
        val hasCodexAuth = authService.isLoggedIn()
        val hasOpenCodeAuth = openCodeCookieStore.load() != null
        val hasOllamaAuth = ollamaCookieStore.loadSessionCookie() != null
        val showCodexSection = hasCodexAuth && !hideOpenAi
        val showOpenCodeSection = hasOpenCodeAuth && !hideOpenCode
        val showOllamaSection = hasOllamaAuth && !hideOllama

        return createPopupStack().apply {
            add(createHeaderRow { openSettings(project, component) { onClosePopup() } })
            add(createSeparatedBlock())

            if (!hasCodexAuth && !hasOpenCodeAuth && !hasOllamaAuth) {
                add(withVerticalInsets(com.intellij.ui.components.JBLabel("Not logged in."), top = 1))
                add(withVerticalInsets(com.intellij.ui.components.ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }, top = 3))
            } else if (!showCodexSection && !showOpenCodeSection && !showOllamaSection) {
                add(withVerticalInsets(com.intellij.ui.components.JBLabel("All quota sources are hidden from this popup."), top = 1))
                add(withVerticalInsets(com.intellij.ui.components.ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }, top = 3))
            } else {
                buildOpenAiPopupContent(currentQuota, currentError, showCodexSection, hasReviewData).forEach { add(it) }
                buildOpenCodePopupContent(openCodeQuota, openCodeError, showOpenCodeSection).forEach { add(it) }
                buildOllamaPopupContent(ollamaQuota, ollamaError, showOllamaSection).forEach { add(it) }

                val updatedAtItems = buildUpdatedAtItems(
                    showCodexSection,
                    currentQuota,
                    showOpenCodeSection,
                    openCodeQuota,
                    showOllamaSection,
                    ollamaQuota,
                )
                if (updatedAtItems.isNotEmpty()) {
                    add(createSeparatedBlock())
                    add(withVerticalInsets(createUpdatedAtRow(updatedAtItems), top = 1))
                }
            }
        }
    }

    private fun buildUpdatedAtItems(
        showCodexSection: Boolean,
        currentQuota: OpenAiCodexQuota?,
        showOpenCodeSection: Boolean,
        openCodeQuota: OpenCodeQuota?,
        showOllamaSection: Boolean,
        ollamaQuota: OllamaQuota?,
    ): List<UpdatedAtItem> {
        val rawItems = mutableListOf<UpdatedAtRawItem>()
        if (showCodexSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("Codex", QuotaIcons.OPENAI), currentQuota?.fetchedAt))
        }
        if (showOpenCodeSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("OpenCode", QuotaIcons.OPENCODE), openCodeQuota?.fetchedAt))
        }
        if (showOllamaSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("Ollama", QuotaIcons.OLLAMA), ollamaQuota?.fetchedAt))
        }
        if (rawItems.isEmpty()) {
            return emptyList()
        }

        val loadedItems = rawItems.filter { it.fetchedAt != null }
        if (loadedItems.size == rawItems.size && loadedItems.areWithinOneMinute()) {
            val newest = loadedItems.maxBy { it.fetchedAt!!.toEpochMilliseconds() }.fetchedAt
            return listOf(
                UpdatedAtItem(
                    icons = loadedItems.map { it.icon },
                    text = QuotaUiUtil.formatInstant(newest) ?: "loading...",
                )
            )
        }

        return rawItems.map { item ->
            UpdatedAtItem(
                icons = listOf(item.icon),
                text = QuotaUiUtil.formatInstant(item.fetchedAt) ?: "loading...",
            )
        }
    }

    private fun List<UpdatedAtRawItem>.areWithinOneMinute(): Boolean {
        val times = mapNotNull { it.fetchedAt?.toEpochMilliseconds() }
        if (times.isEmpty()) return false
        return times.max() - times.min() <= 60_000L
    }
}

private data class UpdatedAtRawItem(
    val icon: UpdatedAtIcon,
    val fetchedAt: Instant?,
)

internal data class QuotaPopupContentState(
    val quota: OpenAiCodexQuota?,
    val error: String?,
    val openCodeQuota: OpenCodeQuota? = null,
    val openCodeError: String? = null,
    val ollamaQuota: OllamaQuota? = null,
    val ollamaError: String? = null,
)

internal class RefreshablePopupPanel<T>(private val renderer: (T) -> JComponent) : com.intellij.util.ui.components.BorderLayoutPanel() {
    private var stableWidth = 0
    private var stableHeight = 0
    private var currentState: T? = null

    init {
        isOpaque = false
    }

    fun refresh(state: T) {
        if (state == currentState) {
            return
        }
        currentState = state
        removeAll()
        addToCenter(renderer(state))
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): java.awt.Dimension {
        val size = super.getPreferredSize()
        stableWidth = maxOf(stableWidth, size.width, JBUI.scale(260))
        stableHeight = maxOf(stableHeight, size.height)
        return java.awt.Dimension(stableWidth, stableHeight)
    }
}
