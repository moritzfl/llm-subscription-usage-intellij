package de.moritzf.quota.idea;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Factory that registers and creates the quota status bar widget.
 */
public final class QuotaStatusBarWidgetFactory implements StatusBarWidgetFactory {
    public static final String ID = "openai.usage.quota.widget";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "OpenAI Usage Quota";
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return !project.isDisposed();
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new QuotaStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
