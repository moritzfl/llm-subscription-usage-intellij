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
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.kimi.KimiQuota
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
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
        val content = RefreshablePopupPanel<QuotaPopupContentState> { state ->
            buildPopupContent(project, component, state.quota, state.error, state.openCodeQuota, state.openCodeError, state.ollamaQuota, state.ollamaError, state.zaiQuota, state.zaiError, state.miniMaxQuota, state.miniMaxError, state.kimiQuota, state.kimiError) { popup?.cancel() }
        }.apply {
            refresh(QuotaPopupContentState(quota, error, openCodeQuota, openCodeError, ollamaQuota, ollamaError, zaiQuota, zaiError, miniMaxQuota, miniMaxError, kimiQuota, kimiError))
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
        var latestZaiQuota = zaiQuota
        var latestZaiError = zaiError
        var latestMiniMaxQuota = miniMaxQuota
        var latestMiniMaxError = miniMaxError
        var latestKimiQuota = kimiQuota
        var latestKimiError = kimiError
        var refreshScheduled = false
        fun scheduleRefresh() {
            if (refreshScheduled) {
                return
            }
            refreshScheduled = true
            ApplicationManager.getApplication().invokeLater {
                refreshScheduled = false
                refreshPopup(currentPopup, content, component, location, latestQuota, latestError, latestOpenCodeQuota, latestOpenCodeError, latestOllamaQuota, latestOllamaError, latestZaiQuota, latestZaiError, latestMiniMaxQuota, latestMiniMaxError, latestKimiQuota, latestKimiError)
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

            override fun onZaiQuotaUpdated(quota: ZaiQuota?, error: String?) {
                latestZaiQuota = quota
                latestZaiError = error
                scheduleRefresh()
            }
            override fun onMiniMaxQuotaUpdated(quota: MiniMaxQuota?, error: String?) {
                latestMiniMaxQuota = quota
                latestMiniMaxError = error
                scheduleRefresh()
            }
            override fun onKimiQuotaUpdated(quota: KimiQuota?, error: String?) {
                latestKimiQuota = quota
                latestKimiError = error
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
        zaiQuota: ZaiQuota?,
        zaiError: String?,
        miniMaxQuota: MiniMaxQuota?,
        miniMaxError: String?,
        kimiQuota: KimiQuota?,
        kimiError: String?,
    ) {
        if (currentPopup.isDisposed || !currentPopup.isVisible) {
            return
        }
        val oldSize = content.preferredSize
        content.refresh(QuotaPopupContentState(quota, error, openCodeQuota, openCodeError, ollamaQuota, ollamaError, zaiQuota, zaiError, miniMaxQuota, miniMaxError, kimiQuota, kimiError))
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
        zaiQuota: ZaiQuota?,
        zaiError: String?,
        miniMaxQuota: MiniMaxQuota?,
        miniMaxError: String?,
        kimiQuota: KimiQuota?,
        kimiError: String?,
        onClosePopup: () -> Unit,
    ): JComponent {
        val settings = QuotaSettingsState.getInstance()
        val hideOpenAi = settings.hideOpenAiFromQuotaPopup
        val hideOpenCode = settings.hideOpenCodeFromQuotaPopup
        val hideOllama = settings.hideOllamaFromQuotaPopup
        val hideZai = settings.hideZaiFromQuotaPopup
        val hideMiniMax = settings.hideMiniMaxFromQuotaPopup
        val hideKimi = settings.hideKimiFromQuotaPopup
        val hasReviewData = currentQuota != null && (
            currentQuota.reviewPrimary != null || currentQuota.reviewSecondary != null ||
                currentQuota.reviewAllowed != null || currentQuota.reviewLimitReached != null
            )

        val authService = QuotaAuthService.getInstance()
        val openCodeCookieStore = OpenCodeSessionCookieStore.getInstance()
        val ollamaCookieStore = OllamaSessionCookieStore.getInstance()
        val zaiApiKeyStore = ZaiApiKeyStore.getInstance()
        val miniMaxApiKeyStore = MiniMaxApiKeyStore.getInstance()
        val kimiCredentialsStore = KimiCredentialsStore.getInstance()
        val hasCodexAuth = authService.isLoggedIn()
        val hasOpenCodeAuth = openCodeCookieStore.load() != null
        val ollamaAuthUnknown = !ollamaCookieStore.isLoaded()
        val hasOllamaAuth = ollamaCookieStore.loadSessionCookie() != null
        val zaiAuthUnknown = !zaiApiKeyStore.isLoaded()
        val hasZaiAuth = zaiApiKeyStore.load() != null
        val miniMaxAuthUnknown = !miniMaxApiKeyStore.isLoaded()
        val hasMiniMaxAuth = !miniMaxApiKeyStore.load().isNullOrBlank()
        val kimiAuthUnknown = !kimiCredentialsStore.isLoaded()
        val hasKimiAuth = kimiCredentialsStore.load()?.isUsable() == true
        val showCodexSection = hasCodexAuth && !hideOpenAi
        val showOpenCodeSection = hasOpenCodeAuth && !hideOpenCode
        val showOllamaSection = (hasOllamaAuth || ollamaAuthUnknown) && !hideOllama
        val showZaiSection = (hasZaiAuth || zaiAuthUnknown) && !hideZai
        val showMiniMaxSection = (hasMiniMaxAuth || miniMaxAuthUnknown) && !hideMiniMax
        val showKimiSection = (hasKimiAuth || kimiAuthUnknown) && !hideKimi

        return createPopupStack().apply {
            add(createHeaderRow { openSettings(project, component) { onClosePopup() } })
            add(createSeparatedBlock())

            if (!hasCodexAuth && !hasOpenCodeAuth && !hasOllamaAuth && !ollamaAuthUnknown && !hasZaiAuth && !zaiAuthUnknown && !hasMiniMaxAuth && !miniMaxAuthUnknown && !hasKimiAuth && !kimiAuthUnknown) {
                add(withVerticalInsets(com.intellij.ui.components.JBLabel("Not logged in."), top = 1))
                add(withVerticalInsets(com.intellij.ui.components.ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }, top = 3))
            } else if (!showCodexSection && !showOpenCodeSection && !showOllamaSection && !showZaiSection && !showMiniMaxSection && !showKimiSection) {
                add(withVerticalInsets(com.intellij.ui.components.JBLabel("All quota sources are hidden from this popup."), top = 1))
                add(withVerticalInsets(com.intellij.ui.components.ActionLink("Open Settings") { openSettings(project, component) { onClosePopup() } }, top = 3))
            } else {
                buildKimiPopupContent(kimiQuota, kimiError, showKimiSection).forEach { add(it) }
                buildMiniMaxPopupContent(miniMaxQuota, miniMaxError, showMiniMaxSection).forEach { add(it) }
                buildOpenAiPopupContent(currentQuota, currentError, showCodexSection, hasReviewData).forEach { add(it) }
                buildOpenCodePopupContent(openCodeQuota, openCodeError, showOpenCodeSection).forEach { add(it) }
                buildOllamaPopupContent(ollamaQuota, ollamaError, showOllamaSection).forEach { add(it) }
                buildZaiPopupContent(zaiQuota, zaiError, showZaiSection).forEach { add(it) }

                val updatedAtItems = buildUpdatedAtItems(
                    showCodexSection,
                    currentQuota,
                    showOpenCodeSection,
                    openCodeQuota,
                    showOllamaSection,
                    ollamaQuota,
                    showZaiSection,
                    zaiQuota,
                    showMiniMaxSection,
                    miniMaxQuota,
                    showKimiSection,
                    kimiQuota,
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
        showZaiSection: Boolean,
        zaiQuota: ZaiQuota?,
        showMiniMaxSection: Boolean,
        miniMaxQuota: MiniMaxQuota?,
        showKimiSection: Boolean,
        kimiQuota: KimiQuota?,
    ): List<UpdatedAtItem> {
        val rawItems = mutableListOf<UpdatedAtRawItem>()
        if (showKimiSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("Kimi", QuotaIcons.KIMI), kimiQuota?.fetchedAt))
        }
        if (showMiniMaxSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("MiniMax", QuotaIcons.MINIMAX), miniMaxQuota?.fetchedAt))
        }
        if (showCodexSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("Codex", QuotaIcons.OPENAI), currentQuota?.fetchedAt))
        }
        if (showOpenCodeSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("OpenCode", QuotaIcons.OPENCODE), openCodeQuota?.fetchedAt))
        }
        if (showOllamaSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("Ollama", QuotaIcons.OLLAMA), ollamaQuota?.fetchedAt))
        }
        if (showZaiSection) {
            rawItems.add(UpdatedAtRawItem(UpdatedAtIcon("Z.ai", QuotaIcons.ZAI), zaiQuota?.fetchedAt))
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
