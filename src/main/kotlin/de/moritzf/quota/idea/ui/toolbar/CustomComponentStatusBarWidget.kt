package de.moritzf.quota.idea.ui.toolbar

import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import org.jetbrains.annotations.Nullable

abstract class CustomComponentStatusBarWidget : CustomStatusBarWidget {
    @Nullable
    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = null
}
