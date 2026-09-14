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
import de.moritzf.quota.idea.common.QuotaProviderRegistry
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
                latestState = service.currentSnapshot()
                scheduleRefresh()
            }

            override fun onQuotaUpdated(type: QuotaProviderType, quota: ProviderQuota?, error: String?, accountId: String) {
                latestState = service.currentSnapshot()
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
    content: JComponent,
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

    private val sections = linkedMapOf<String, ProviderPopupSection>()
    private val sectionTypes = linkedMapOf<String, QuotaProviderType>()

    private val updatedAtSeparator = createSeparatedBlock()
    private val updatedAtRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply { isOpaque = false }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8, 5, 8)
        add(header)
        add(notLoggedInPanel)
        add(allHiddenPanel)
        add(updatedAtSeparator)
        add(updatedAtRow)
        rebuildSections(QuotaSettingsState.getInstance())
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(JBUI.scale(280), size.height)
    }

    fun update(state: QuotaUsageSnapshot) {
        val settings = QuotaSettingsState.getInstance()
        rebuildSections(settings)
        val visibleSections = sections.keys.associateWith { id -> isSectionVisible(settings, id) }

        val notLoggedIn = visibleSections.isEmpty() || visibleSections.values.none { it } &&
            sections.keys.all { id -> !isAccountAuthenticated(settings, id) }
        val allHidden = sections.isNotEmpty() && visibleSections.values.none { it } &&
            sections.keys.any { id -> isAccountAuthenticated(settings, id) }

        notLoggedInPanel.isVisible = notLoggedIn
        allHiddenPanel.isVisible = !notLoggedIn && allHidden
        sections.forEach { (id, section) ->
            val type = sectionTypes[id]
            val account = settings.account(id)
            section.accountId = id
            section.accountTitle = account?.let { acc ->
                acc.providerType()?.let { type ->
                    if (settings.accountTypeHasDuplicates(type)) acc.name else null
                }
            }
            val snapshot = state.forAccount(id, type)
            section.update(snapshot.quota, snapshot.error, visibleSections[id] == true)
        }

        val showAnySection = visibleSections.values.any { it }
        val updatedAtItems = if (showAnySection) buildUpdatedAtItems(state, visibleSections, settings) else emptyList()

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

    private fun rebuildSections(settings: QuotaSettingsState) {
        val desired = if (settings.accounts.isNotEmpty()) {
            settings.accounts.mapNotNull { account ->
                val type = account.providerType() ?: return@mapNotNull null
                account.id to type
            }
        } else {
            QuotaProviderRegistry.defaultProviderOrder().map { it.id to it }
        }
        if (desired.map { it.first } == sections.keys.toList()) {
            return
        }
        sections.values.forEach(::remove)
        sections.clear()
        sectionTypes.clear()
        val insertAt = getComponentZOrder(updatedAtSeparator).takeIf { it >= 0 } ?: componentCount
        desired.forEachIndexed { index, (id, type) ->
            val section = ProviderUiRegistry.forType(type).createPopupSection()
            sections[id] = section
            sectionTypes[id] = type
            add(section, insertAt + index)
        }
        revalidate()
    }

    private fun isSectionVisible(settings: QuotaSettingsState, id: String): Boolean {
        val account = settings.account(id)
        if (account?.hiddenFromPopup == true) return false
        val type = sectionTypes[id] ?: account?.providerType() ?: QuotaProviderType.fromId(id)
        if (type != null && account == null && settings.isHiddenFromPopup(type)) return false
        return isAccountAuthenticated(settings, id)
    }

    private fun isAccountAuthenticated(settings: QuotaSettingsState, id: String): Boolean {
        val type = sectionTypes[id] ?: settings.account(id)?.providerType() ?: QuotaProviderType.fromId(id)
            ?: return false
        return ProviderUiRegistry.forType(type).authState(id) != ProviderAuthState.UNAUTHENTICATED
    }

    private fun buildUpdatedAtItems(
        state: QuotaUsageSnapshot,
        visibleSections: Map<String, Boolean>,
        settings: QuotaSettingsState,
    ): List<UpdatedAtItem> {
        val order = if (settings.accounts.isNotEmpty()) {
            settings.accounts.map { it.id }
        } else {
            QuotaProviderRegistry.defaultProviderOrder().map { it.id }
        }
        val rawItems = order.mapNotNull { id ->
            if (visibleSections[id] != true) return@mapNotNull null
            val type = sectionTypes[id] ?: return@mapNotNull null
            val ui = ProviderUiRegistry.forType(type)
            val fetchedAt = state.forAccount(id, type).quota?.fetchedAt
            UpdatedAtRawItem(UpdatedAtIcon(ui.updatedAtLabel, ui.icon), fetchedAt)
        }
        if (rawItems.isEmpty()) {
            return emptyList()
        }

        return groupUpdatedAtItems(
            rawItems.map { item ->
                UpdatedAtItem(
                    icons = listOf(item.icon),
                    text = QuotaUiUtil.formatInstant(item.fetchedAt) ?: "loading...",
                )
            },
        )
    }
}

private data class UpdatedAtRawItem(
    val icon: UpdatedAtIcon,
    val fetchedAt: kotlin.time.Instant?,
)
