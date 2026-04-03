package de.moritzf.quota.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.util.concurrency.AppExecutorUtil;
import de.moritzf.quota.OpenAiCodexQuota;
import de.moritzf.quota.OpenAiCodexQuotaClient;
import de.moritzf.quota.OpenAiCodexQuotaException;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically fetches quota data and publishes updates to the IDE message bus.
 */
@Service(Service.Level.APP)
public final class QuotaUsageService implements Disposable {
    private final OpenAiCodexQuotaClient client = new OpenAiCodexQuotaClient();
    private final ScheduledExecutorService scheduler = AppExecutorUtil.getAppScheduledExecutorService();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final AtomicReference<OpenAiCodexQuota> lastQuota = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastResponseJson = new AtomicReference<>();
    private ScheduledFuture<?> scheduled;

    public static QuotaUsageService getInstance() {
        return ApplicationManager.getApplication().getService(QuotaUsageService.class);
    }

    public QuotaUsageService() {
        scheduleRefresh();
    }

    public @Nullable OpenAiCodexQuota getLastQuota() {
        return lastQuota.get();
    }

    public @Nullable String getLastError() {
        return lastError.get();
    }

    public @Nullable String getLastResponseJson() {
        return lastResponseJson.get();
    }

    public void refreshNowAsync() {
        AppExecutorUtil.getAppExecutorService().execute(this::refreshNow);
    }

    public void refreshNowBlocking() {
        refreshNow();
    }

    private void scheduleRefresh() {
        int minutes = Math.max(1, QuotaSettingsState.getInstance().refreshMinutes);
        scheduled = scheduler.scheduleWithFixedDelay(this::refreshNow, 0, minutes, TimeUnit.MINUTES);
    }

    private void refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            QuotaAuthService authService = QuotaAuthService.getInstance();
            String accessToken = authService.getAccessTokenBlocking();
            if (accessToken == null || accessToken.isBlank()) {
                publishUpdate(null, "Not logged in");
                return;
            }
            String accountId = authService.getAccountId();
            OpenAiCodexQuota quota = client.fetchQuota(accessToken, accountId);
            publishUpdate(quota, null);
        } catch (OpenAiCodexQuotaException exception) {
            publishUpdate(null, "Request failed (" + exception.getStatusCode() + ")");
        } catch (Exception exception) {
            String message = exception.getMessage();
            publishUpdate(null, Objects.requireNonNullElse(message, "Request failed"));
        } finally {
            refreshing.set(false);
        }
    }

    private void publishUpdate(@Nullable OpenAiCodexQuota quota, @Nullable String error) {
        if (quota != null && quota.getRawJson() != null) {
            lastResponseJson.set(quota.getRawJson());
        }
        lastQuota.set(quota);
        lastError.set(error);
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication()
                        .getMessageBus()
                        .syncPublisher(QuotaUsageListener.TOPIC)
                        .onQuotaUpdated(quota, error));
    }

    @Override
    public void dispose() {
        if (scheduled != null) {
            scheduled.cancel(true);
            scheduled = null;
        }
    }
}
