package de.moritzf.quota.idea.ui.popup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaSessionCookieStore
import de.moritzf.quota.idea.opencode.OpenCodeSessionCookieStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.QuotaIcons
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.zai.ZaiQuota
import kotlinx.datetime.Instant
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.openapi.ui.VerticalFlowLayout

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
        zaiQuota: ZaiQuota?,
        zaiError: String?,
        miniMaxQuota: MiniMaxQuota?,
        miniMaxError: String?,
        kimiQuota: KimiQuota?,
        kimiError: String?,
        location: QuotaPopupLocation,
    ) {
        if (project.isDisposed) {
            return
        }

        QuotaUsageService.getInstance().refreshNowAsync()
        var popup: JBPopup? = null

        val contentPanel = QuotaPopupContentPanel(project, component) { popup?.cancel() }
        val content = RefreshablePopupPanel<QuotaPopupContentState>(contentPanel) { state ->
            contentPanel.update(state)
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
        var latestState = QuotaPopupContentState(
            quota, error, openCodeQuota, openCodeError, ollamaQuota, ollamaError,
            zaiQuota, zaiError, miniMaxQuota, miniMaxError, kimiQuota, kimiError,
        )
        var refreshScheduled = false
        fun scheduleRefresh() {
            if (refreshScheduled) {
                return
            }
            refreshScheduled = true
            ApplicationManager.getApplication().invokeLater {
                refreshScheduled = false
                refreshPopup(currentPopup, content, component, location, latestState)
            }
        }
        popupConnection.subscribe(QuotaUsageListener.TOPIC, object : QuotaUsageListener {
            override fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?) {
                latestState = latestState.copy(quota = quota, error = error)
                scheduleRefresh()
            }

            override fun onOpenCodeQuotaUpdated(quota: OpenCodeQuota?, error: String?) {
                latestState = latestState.copy(openCodeQuota = quota, openCodeError = error)
                scheduleRefresh()
            }

            override fun onOllamaQuotaUpdated(quota: OllamaQuota?, error: String?) {
                latestState = latestState.copy(ollamaQuota = quota, ollamaError = error)
                scheduleRefresh()
            }

            override fun onZaiQuotaUpdated(quota: ZaiQuota?, error: String?) {
                latestState = latestState.copy(zaiQuota = quota, zaiError = error)
                scheduleRefresh()
            }

            override fun onMiniMaxQuotaUpdated(quota: MiniMaxQuota?, error: String?) {
                latestState = latestState.copy(miniMaxQuota = quota, miniMaxError = error)
                scheduleRefresh()
            }

            override fun onKimiQuotaUpdated(quota: KimiQuota?, error: String?) {
                latestState = latestState.copy(kimiQuota = quota, kimiError = error)
                scheduleRefresh()
            }
        })

        content.refresh(latestState)
        popup.show(RelativePoint(component, popupPoint(component, content, location)))
    }

    private fun refreshPopup(
        currentPopup: JBPopup,
        content: RefreshablePopupPanel<QuotaPopupContentState>,
        component: Component,
        location: QuotaPopupLocation,
        state: QuotaPopupContentState,
    ) {
        if (currentPopup.isDisposed || !currentPopup.isVisible) {
            return
        }
        val oldHeight = content.preferredSize.height
        content.refresh(state)
        val newHeight = content.preferredSize.height
        if (oldHeight != newHeight) {
            currentPopup.size = Dimension(JBUI.scale(280), newHeight)
            val newPoint = popupPoint(component, content, location)
            val screenPoint = RelativePoint(component, newPoint).getScreenPoint()
            currentPopup.setLocation(screenPoint)
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
}

internal data class QuotaPopupContentState(
    val quota: OpenAiCodexQuota?,
    val error: String?,
    val openCodeQuota: OpenCodeQuota? = null,
    val openCodeError: String? = null,
    val ollamaQuota: OllamaQuota? = null,
    val ollamaError: String? = null,
    val zaiQuota: ZaiQuota? = null,
    val zaiError: String? = null,
    val miniMaxQuota: MiniMaxQuota? = null,
    val miniMaxError: String? = null,
    val kimiQuota: KimiQuota? = null,
    val kimiError: String? = null,
)

internal class RefreshablePopupPanel<T>(
    private val content: JComponent,
    private val updater: (T) -> Unit,
) : BorderLayoutPanel() {
    init {
        isOpaque = false
        addToCenter(content)
    }

    fun refresh(state: T) {
        updater(state)
        invalidate()
        validate()
        repaint()
    }
}

private class QuotaPopupContentPanel(
    private val project: Project,
    private val component: Component,
    private val onClosePopup: () -> Unit,
) : JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)) {

    private val header = createHeaderRow { openSettings(project, component) { onClosePopup() } }

    private val notLoggedInPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
        isOpaque = false
        add(JBLabel("Not logged in.").apply { border = JBUI.Borders.emptyTop(1) })
        add(ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }.apply { border = JBUI.Borders.emptyTop(3) })
    }

    private val allHiddenPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
        isOpaque = false
        add(JBLabel("All quota sources are hidden from this popup.").apply { border = JBUI.Borders.emptyTop(1) })
        add(ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }.apply { border = JBUI.Borders.emptyTop(3) })
    }

    private val sections = linkedMapOf(
        QuotaProviderType.KIMI to KimiPopupSection(),
        QuotaProviderType.MINIMAX to MiniMaxPopupSection(),
        QuotaProviderType.OPEN_AI to OpenAiPopupSection(),
        QuotaProviderType.OPEN_CODE to OpenCodePopupSection(),
        QuotaProviderType.OLLAMA to OllamaPopupSection(),
        QuotaProviderType.ZAI to ZaiPopupSection(),
    )

    private val updatedAtSeparator = createSeparatedBlock()
    private val updatedAtRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply { isOpaque = false }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8, 5, 8)
        add(header)
        add(notLoggedInPanel)
        add(allHiddenPanel)
        val order = QuotaSettingsState.getInstance().providerOrderList()
        order.forEach { id ->
            sections[id]?.let { add(it) }
        }
        sections.keys.filter { it !in order }.forEach { id ->
            sections[id]?.let { add(it) }
        }
        add(updatedAtSeparator)
        add(updatedAtRow)
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(JBUI.scale(280), size.height)
    }

    fun update(state: QuotaPopupContentState) {
        val settings = QuotaSettingsState.getInstance()
        val hideOpenAi = settings.hideOpenAiFromQuotaPopup
        val hideOpenCode = settings.hideOpenCodeFromQuotaPopup
        val hideOllama = settings.hideOllamaFromQuotaPopup
        val hideZai = settings.hideZaiFromQuotaPopup
        val hideMiniMax = settings.hideMiniMaxFromQuotaPopup
        val hideKimi = settings.hideKimiFromQuotaPopup
        val hasReviewData = state.quota != null && (
            state.quota.reviewPrimary != null || state.quota.reviewSecondary != null ||
                state.quota.reviewAllowed != null || state.quota.reviewLimitReached != null
            )

        val authService = QuotaAuthService.getInstance()
        val openCodeCookieStore = OpenCodeSessionCookieStore.getInstance()
        val ollamaCookieStore = OllamaSessionCookieStore.getInstance()
        val zaiApiKeyStore = ZaiApiKeyStore.getInstance()
        val miniMaxApiKeyStore = MiniMaxApiKeyStore.getInstance()
        val kimiCredentialsStore = KimiCredentialsStore.getInstance()
        val hasCodexAuth = authService.isLoggedIn()
        val hasOpenCodeAuth = openCodeCookieStore.load() != null
        val hasOllamaAuth = ollamaCookieStore.loadSessionCookie() != null
        val ollamaAuthUnknown = !ollamaCookieStore.isLoaded()
        val hasZaiAuth = zaiApiKeyStore.load() != null
        val zaiAuthUnknown = !zaiApiKeyStore.isLoaded()
        val hasMiniMaxAuth = !miniMaxApiKeyStore.load().isNullOrBlank()
        val miniMaxAuthUnknown = !miniMaxApiKeyStore.isLoaded()
        val hasKimiAuth = kimiCredentialsStore.load()?.isUsable() == true
        val kimiAuthUnknown = !kimiCredentialsStore.isLoaded()
        val showCodexSection = hasCodexAuth && !hideOpenAi
        val showOpenCodeSection = hasOpenCodeAuth && !hideOpenCode
        val showOllamaSection = (hasOllamaAuth || ollamaAuthUnknown) && !hideOllama
        val showZaiSection = (hasZaiAuth || zaiAuthUnknown) && !hideZai
        val showMiniMaxSection = (hasMiniMaxAuth || miniMaxAuthUnknown) && !hideMiniMax
        val showKimiSection = (hasKimiAuth || kimiAuthUnknown) && !hideKimi

        val notLoggedIn = !hasCodexAuth && !hasOpenCodeAuth && !hasOllamaAuth && !ollamaAuthUnknown &&
            !hasZaiAuth && !zaiAuthUnknown && !hasMiniMaxAuth && !miniMaxAuthUnknown && !hasKimiAuth && !kimiAuthUnknown
        val allHidden = !showCodexSection && !showOpenCodeSection && !showOllamaSection && !showZaiSection &&
            !showMiniMaxSection && !showKimiSection

        notLoggedInPanel.isVisible = notLoggedIn
        allHiddenPanel.isVisible = !notLoggedIn && allHidden

        (sections[QuotaProviderType.KIMI] as? KimiPopupSection)?.update(state.kimiQuota, state.kimiError, showKimiSection)
        (sections[QuotaProviderType.MINIMAX] as? MiniMaxPopupSection)?.update(state.miniMaxQuota, state.miniMaxError, showMiniMaxSection)
        (sections[QuotaProviderType.OPEN_AI] as? OpenAiPopupSection)?.update(state.quota, state.error, showCodexSection, hasReviewData)
        (sections[QuotaProviderType.OPEN_CODE] as? OpenCodePopupSection)?.update(state.openCodeQuota, state.openCodeError, showOpenCodeSection)
        (sections[QuotaProviderType.OLLAMA] as? OllamaPopupSection)?.update(state.ollamaQuota, state.ollamaError, showOllamaSection)
        (sections[QuotaProviderType.ZAI] as? ZaiPopupSection)?.update(state.zaiQuota, state.zaiError, showZaiSection)

        val showAnySection = !notLoggedIn && !allHidden
        val updatedAtItems = if (showAnySection) buildUpdatedAtItems(
            showKimiSection, state.kimiQuota,
            showMiniMaxSection, state.miniMaxQuota,
            showCodexSection, state.quota,
            showOpenCodeSection, state.openCodeQuota,
            showOllamaSection, state.ollamaQuota,
            showZaiSection, state.zaiQuota,
        ) else emptyList()

        updatedAtSeparator.isVisible = updatedAtItems.isNotEmpty()
        updatedAtRow.isVisible = updatedAtItems.isNotEmpty()
        if (updatedAtItems.isNotEmpty()) {
            updatedAtRow.removeAll()
            updatedAtRow.add(JBLabel("Updated:").apply { foreground = com.intellij.ui.JBColor.GRAY })
            updatedAtItems.forEachIndexed { index, item ->
                item.icons.forEach { providerIcon ->
                    updatedAtRow.add(JBLabel().apply {
                        icon = de.moritzf.quota.idea.ui.indicator.scaleIconToQuotaStatusSize(providerIcon.icon, this)
                        toolTipText = providerIcon.label
                    })
                }
                updatedAtRow.add(JBLabel(item.text).apply { foreground = com.intellij.ui.JBColor.GRAY })
                if (index < updatedAtItems.lastIndex) {
                    updatedAtRow.add(JBLabel(";").apply { foreground = com.intellij.ui.JBColor.GRAY })
                }
            }
        }
    }

    private fun buildUpdatedAtItems(
        showKimiSection: Boolean,
        kimiQuota: KimiQuota?,
        showMiniMaxSection: Boolean,
        miniMaxQuota: MiniMaxQuota?,
        showCodexSection: Boolean,
        currentQuota: OpenAiCodexQuota?,
        showOpenCodeSection: Boolean,
        openCodeQuota: OpenCodeQuota?,
        showOllamaSection: Boolean,
        ollamaQuota: OllamaQuota?,
        showZaiSection: Boolean,
        zaiQuota: ZaiQuota?,
    ): List<UpdatedAtItem> {
        val order = QuotaSettingsState.getInstance().providerOrderList()
        val providerMap = mapOf(
            QuotaProviderType.KIMI to Pair(showKimiSection, UpdatedAtRawItem(UpdatedAtIcon("Kimi", QuotaIcons.KIMI), kimiQuota?.fetchedAt)),
            QuotaProviderType.MINIMAX to Pair(showMiniMaxSection, UpdatedAtRawItem(UpdatedAtIcon("MiniMax", QuotaIcons.MINIMAX), miniMaxQuota?.fetchedAt)),
            QuotaProviderType.OPEN_AI to Pair(showCodexSection, UpdatedAtRawItem(UpdatedAtIcon("Codex", QuotaIcons.OPENAI), currentQuota?.fetchedAt)),
            QuotaProviderType.OPEN_CODE to Pair(showOpenCodeSection, UpdatedAtRawItem(UpdatedAtIcon("OpenCode", QuotaIcons.OPENCODE), openCodeQuota?.fetchedAt)),
            QuotaProviderType.OLLAMA to Pair(showOllamaSection, UpdatedAtRawItem(UpdatedAtIcon("Ollama", QuotaIcons.OLLAMA), ollamaQuota?.fetchedAt)),
            QuotaProviderType.ZAI to Pair(showZaiSection, UpdatedAtRawItem(UpdatedAtIcon("Z.ai", QuotaIcons.ZAI), zaiQuota?.fetchedAt)),
        )
        val rawItems = order.mapNotNull { id ->
            val (show, item) = providerMap[id] ?: return@mapNotNull null
            if (show) item else null
        }
        if (rawItems.isEmpty()) {
            return emptyList()
        }

        val loadedItems = rawItems.filter { it.fetchedAt != null }
        if (loadedItems.isEmpty()) {
            return listOf(
                UpdatedAtItem(
                    icons = rawItems.map { it.icon },
                    text = "loading...",
                )
            )
        }
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
