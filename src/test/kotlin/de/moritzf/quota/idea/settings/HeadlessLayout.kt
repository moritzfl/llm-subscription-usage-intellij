package de.moritzf.quota.idea.settings

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import javax.swing.JSeparator

/** Lays out a headless Swing tree the way a shown window would. */
internal fun layoutHeadless(root: Container, passes: Int = 3) {
    // Without a peer, Swing reports a separator's current size as its minimum, so a form could
    // never shrink after its first layout. Shown separators get 1x1 from their peer.
    root.descendants().filterIsInstance<JSeparator>().forEach {
        if (!it.isMinimumSizeSet) it.minimumSize = Dimension(1, 1)
    }
    repeat(passes) { layoutTree(root) }
}

internal fun Container.descendants(): List<Component> = components.flatMap {
    listOf(it) + ((it as? Container)?.descendants() ?: emptyList())
}

private fun layoutTree(container: Container) {
    container.invalidate()
    container.doLayout()
    container.components.filterIsInstance<Container>().forEach(::layoutTree)
}
