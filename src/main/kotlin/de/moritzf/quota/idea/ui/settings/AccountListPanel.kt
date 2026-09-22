package de.moritzf.quota.idea.ui.settings

import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnActionButton
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

internal class AccountListPanel(
    private val onAccountSelected: (ProviderAccount?) -> Unit,
    private val onAccountsChanged: () -> Unit,
    private val onBeforeRemove: () -> Unit = {},
) : JPanel(BorderLayout()) {
    private val pendingRemovals = mutableListOf<ProviderAccount>()
    private val pendingAdds = mutableListOf<ProviderAccount>()
    private var persistedIds = emptySet<String>()
    private val model = CollectionListModel<ProviderAccount>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = AccountCellRenderer()
        emptyText.text = "Add a provider"
        accessibleContext.accessibleName = "Accounts"
    }

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(260), JBUI.scale(200))
        minimumSize = Dimension(JBUI.scale(200), JBUI.scale(80))
        val header = JBLabel("Accounts").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyBottom(6)
        }
        val decorator = ToolbarDecorator.createDecorator(list)
            .setAddAction { button -> showTypeChooser(button) }
            .setRemoveAction { removeSelected() }
            .setMoveUpAction { moveSelected(-1) }
            .setMoveDownAction { moveSelected(1) }
            .createPanel()
        add(header, BorderLayout.NORTH)
        add(decorator, BorderLayout.CENTER)
        list.addListSelectionListener {
            if (!list.valueIsAdjusting) {
                onAccountSelected(list.selectedValue)
            }
        }
        reloadFromSettings()
    }

    fun selectedAccount(): ProviderAccount? = list.selectedValue

    fun accounts(): List<ProviderAccount> = model.items.toList()

    fun reloadFromSettings() {
        val selectedId = list.selectedValue?.id
        val state = QuotaSettingsState.getInstance()
        persistedIds = state.accounts.map { it.id }.toSet()
        pendingRemovals.clear()
        pendingAdds.clear()
        model.replaceAll(state.accounts.map { it.snapshot() })
        val restore = model.items.firstOrNull { it.id == selectedId } ?: model.items.firstOrNull()
        if (restore != null) {
            list.setSelectedValue(restore, true)
        } else {
            list.clearSelection()
            onAccountSelected(null)
        }
        list.repaint()
    }

    fun refreshStatuses() {
        list.repaint()
    }

    fun isStructurallyModified(state: QuotaSettingsState): Boolean {
        if (pendingRemovals.isNotEmpty() || pendingAdds.isNotEmpty()) return true
        return model.items.map { it.id } != state.accounts.map { it.id }
    }

    fun isModifiedVs(state: QuotaSettingsState): Boolean {
        if (isStructurallyModified(state)) return true
        return model.items.zip(state.accounts).any { (draft, persisted) ->
            draft.name.trim() != persisted.name.trim() ||
                draft.isDefault != persisted.isDefault ||
                draft.allowFailover != persisted.allowFailover ||
                draft.hiddenFromPopup != persisted.hiddenFromPopup ||
                draft.extras != persisted.extras
        }
    }

    fun listLabel(account: ProviderAccount): String {
        val type = account.providerType() ?: return account.name
        return if (model.items.count { it.typeId == type.id } > 1) {
            "${type.displayName} (${account.name})"
        } else {
            type.displayName
        }
    }

    fun hasDuplicateNames(): Boolean {
        return model.items.any { account ->
            val name = account.name.trim()
            if (name.isEmpty()) return@any true
            model.items.any { other ->
                other.id != account.id &&
                    other.typeId == account.typeId &&
                    other.name.trim().equals(name, ignoreCase = true)
            }
        }
    }

    fun applyPendingChanges(state: QuotaSettingsState) {
        val keptIds = model.items.map { it.id }.toSet()
        pendingRemovals.filter { it.id !in keptIds }.forEach { account ->
            runCatching { de.moritzf.quota.idea.settings.AccountSecrets.clear(account) }
            state.dropAccountData(account.id)
            runCatching { QuotaUsageService.getInstance().clearUsageData(account.id) }
        }
        pendingRemovals.clear()
        pendingAdds.clear()
        state.accounts = QuotaSettingsState.sanitizeAccounts(model.items.map { it.snapshot() }).toMutableList()
        state.syncLegacyAccountFields()
        state.pruneOrphanAccountData()
        runCatching { QuotaUsageService.getInstance().syncAccounts() }
    }

    fun discardPending() {
        (pendingAdds + pendingRemovals)
            .filter { it.id !in persistedIds }
            .distinctBy { it.id }
            .forEach { account ->
                runCatching { de.moritzf.quota.idea.settings.AccountSecrets.clear(account) }
            }
        pendingAdds.clear()
        pendingRemovals.clear()
    }

    private fun addAccount(type: QuotaProviderType) {
        val siblings = model.items.filter { it.typeId == type.id }
        if (!ProviderCatalog.get(type).capabilities.multipleAccounts && siblings.isNotEmpty()) return
        val reuseTypeId = siblings.isEmpty() && pendingRemovals.none { it.id == type.id }
        val created = ProviderAccount.create(
            type,
            QuotaSettingsState.getInstance().suggestedAccountName(type, siblings.map { it.name }),
            isFirstOfType = reuseTypeId,
        )
        pendingAdds += created
        model.add(created)
        list.setSelectedValue(created, true)
        onAccountsChanged()
    }

    private fun removeSelected() {
        val account = list.selectedValue ?: return
        val label = listLabel(account)
        val confirmed = Messages.showYesNoDialog(
            this,
            if (account.providerType() == QuotaProviderType.ANTIGRAVITY) {
                "Remove $label quota tracking? Your AGY CLI login will remain available."
            } else "Remove $label and delete its stored login?",
            "Remove account",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!confirmed) return
        onBeforeRemove()
        pendingRemovals += account
        pendingAdds.removeAll { it.id == account.id }
        model.remove(account)
        val siblings = model.items.filter { it.typeId == account.typeId }
        if (account.isDefault && siblings.isNotEmpty() && siblings.none { it.isDefault }) {
            siblings.first().isDefault = true
            siblings.first().allowFailover = false
        }
        onAccountsChanged()
    }

    private fun moveSelected(delta: Int) {
        val index = list.selectedIndex
        val target = index + delta
        if (index < 0 || target !in 0 until model.size) return
        val item = model.getElementAt(index)
        model.remove(index)
        model.add(target, item)
        list.selectedIndex = target
        onAccountsChanged()
    }

    private fun showTypeChooser(button: AnActionButton) {
        val types = QuotaProviderType.defaultProviderOrder().filter { type ->
            ProviderCatalog.get(type).capabilities.multipleAccounts || model.items.none { it.typeId == type.id }
        }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(types)
            .setTitle("Add provider")
            .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            .setRenderer(ProviderTypeCellRenderer())
            .setNamerForFiltering { it.displayName }
            .setItemChosenCallback { type -> addAccount(type) }
            .setMovable(false)
            .setRequestFocus(true)
            .createPopup()
        popup.show(button.preferredPopupPoint)
    }

    private class ProviderTypeCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val type = value as? QuotaProviderType ?: return component
            text = type.displayName
            icon = scaledListIcon(type, this)
            border = JBUI.Borders.empty(2, 8)
            return component
        }
    }

    private class AccountCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val account = value as? ProviderAccount ?: return component
            val items = (list.model as? CollectionListModel<*>)?.items.orEmpty()
            val type = account.providerType()
            text = if (type != null && items.count { (it as? ProviderAccount)?.typeId == type.id } > 1) {
                "${type.displayName} (${account.name})"
            } else {
                type?.displayName ?: account.name
            }
            if (type != null) {
                icon = scaledListIcon(type, this)
            }
            return component
        }
    }

    companion object {
        private const val LIST_ICON_SIZE = 16

        private fun scaledListIcon(type: QuotaProviderType, component: JComponent): Icon {
            return ProviderReorderPanel.scaleToSize(
                ProviderUiRegistry.forType(type).icon,
                JBUI.scale(LIST_ICON_SIZE),
                component,
            )
        }
    }
}
