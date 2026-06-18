package de.moritzf.quota.idea.ui.popup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import de.moritzf.quota.idea.common.ProviderSnapshot
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageListener
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.common.QuotaUsageSnapshot
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.QuotaUiUtil
import de.moritzf.quota.idea.ui.indicator.ProviderAuthState
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import de.moritzf.quota.shared.ProviderQuota
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel

internal enum class QuotaPopupLocation {
    ABOVE,
    BELOW,
}

internal object QuotaPopupSupport {
    fun showPopup(
        project: Project,
        component: Component,
        location: QuotaPopupLocation,
    ) {
        if (project.isDisposed) {
            return
        }

        val service = QuotaUsageService.getInstance()
        service.refreshNowAsync()
        var popup: JBPopup? = null

        val contentPanel = QuotaPopupContentPanel(project, component) { popup?.cancel() }
        val content = RefreshablePopupPanel<QuotaUsageSnapshot>(contentPanel) { state ->
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
        var latestState = service.currentSnapshot()
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
            override fun onQuotaUpdated(type: QuotaProviderType, quota: ProviderQuota?, error: String?) {
                latestState = QuotaUsageSnapshot(latestState.entries + (type to ProviderSnapshot(quota, error)))
                scheduleRefresh()
            }
        })

        content.refresh(latestState)
        popup.show(RelativePoint(component, popupPoint(component, content, location)))
    }

    private fun refreshPopup(
        currentPopup: JBPopup,
        content: RefreshablePopupPanel<QuotaUsageSnapshot>,
        component: Component,
        location: QuotaPopupLocation,
        state: QuotaUsageSnapshot,
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

    private val sections: Map<QuotaProviderType, ProviderPopupSection> =
        QuotaProviderType.alphabeticalOrder().associateWith { type ->
            ProviderUiRegistry.forType(type).createPopupSection()
        }

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

    fun update(state: QuotaUsageSnapshot) {
        val settings = QuotaSettingsState.getInstance()
        val authStates = ProviderUiRegistry.all.mapValues { (_, ui) -> ui.authState() }
        val visibleSections = sections.keys.associateWith { type ->
            authStates[type] != ProviderAuthState.UNAUTHENTICATED && !settings.isHiddenFromPopup(type)
        }

        val notLoggedIn = authStates.values.all { it == ProviderAuthState.UNAUTHENTICATED }
        val allHidden = visibleSections.values.none { it }

        notLoggedInPanel.isVisible = notLoggedIn
        allHiddenPanel.isVisible = !notLoggedIn && allHidden
        sections.forEach { (type, section) ->
            val snapshot = state[type]
            section.update(snapshot.quota, snapshot.error, visibleSections[type] == true)
        }

        val showAnySection = !notLoggedIn && !allHidden
        val updatedAtItems = if (showAnySection) buildUpdatedAtItems(state, visibleSections) else emptyList()

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
        state: QuotaUsageSnapshot,
        visibleSections: Map<QuotaProviderType, Boolean>,
    ): List<UpdatedAtItem> {
        val order = QuotaSettingsState.getInstance().providerOrderList()
        val rawItems = order.mapNotNull { type ->
            if (visibleSections[type] != true) return@mapNotNull null
            val ui = ProviderUiRegistry.forType(type)
            UpdatedAtRawItem(UpdatedAtIcon(ui.updatedAtLabel, ui.icon), state[type].quota?.fetchedAt)
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
    val fetchedAt: kotlin.time.Instant?,
)
