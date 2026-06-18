package de.moritzf.quota.idea.ui.toolbar;

import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget;
import org.jetbrains.annotations.Nullable;

public abstract class CustomComponentStatusBarWidget implements CustomStatusBarWidget {
    @Override
    public @Nullable StatusBarWidget.WidgetPresentation getPresentation() {
        return null;
    }
}
