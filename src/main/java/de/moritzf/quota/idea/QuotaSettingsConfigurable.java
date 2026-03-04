package de.moritzf.quota.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;

/**
 * Settings UI that manages authentication actions and shows latest quota payload data.
 */
public final class QuotaSettingsConfigurable implements Configurable {
    private JPanel panel;
    private JBTextField accountIdField;
    private JBTextField emailField;
    private JBTextArea responseArea;
    private JBLabel loginHeaderLabel;
    private JBLabel statusLabel;
    private JButton loginButton;
    private JButton cancelLoginButton;
    private JButton logoutButton;
    private MessageBusConnection connection;

    @Override
    public String getDisplayName() {
        return "OpenAI Usage Quota";
    }

    @Override
    public @Nullable JComponent createComponent() {
        accountIdField = new JBTextField();
        accountIdField.setEditable(false);
        emailField = new JBTextField();
        emailField.setEditable(false);
        responseArea = new JBTextArea();
        responseArea.setEditable(false);
        responseArea.setLineWrap(false);
        loginHeaderLabel = new JBLabel();
        statusLabel = new JBLabel();
        loginButton = new JButton("Log In");
        cancelLoginButton = new JButton("Cancel Login");
        logoutButton = new JButton("Log Out");

        loginButton.addActionListener(event -> {
            QuotaAuthService authService = QuotaAuthService.getInstance();
            if (authService.isLoggedIn()) {
                updateAuthUi();
                return;
            }
            loginButton.setEnabled(false);
            statusLabel.setText("Opening browser...");
            authService.startLoginFlow(result -> {
                if (panel == null) {
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (panel == null || statusLabel == null || loginButton == null || logoutButton == null) {
                        return;
                    }
                    if (result.success) {
                        statusLabel.setText("Logged in");
                        QuotaUsageService.getInstance().refreshNowAsync();
                    } else {
                        String message = result.message != null ? result.message : "Login failed";
                        statusLabel.setText("Login failed");
                        Messages.showErrorDialog(panel, message, "OpenAI Login");
                    }
                    loginButton.setEnabled(true);
                    updateAuthUi();
                    updateAccountFields();
                }, ModalityState.stateForComponent(panel));
            });
            updateAuthUi();
        });

        cancelLoginButton.addActionListener(event -> {
            boolean aborted = QuotaAuthService.getInstance().abortLogin("Login canceled");
            statusLabel.setText(aborted ? "Login canceled" : "No login in progress");
            updateAuthUi();
        });

        logoutButton.addActionListener(event -> {
            QuotaAuthService.getInstance().clearCredentials();
            updateAuthUi();
            updateAccountFields();
            QuotaUsageService.getInstance().refreshNowAsync();
        });

        JPanel authPanel = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 8, 0));
        authPanel.add(loginButton);
        authPanel.add(cancelLoginButton);
        authPanel.add(logoutButton);

        JBScrollPane responseScroll = new JBScrollPane(responseArea);
        responseScroll.setHorizontalScrollBarPolicy(JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        responseScroll.setVerticalScrollBarPolicy(JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        panel = FormBuilder.createFormBuilder()
                .addComponent(loginHeaderLabel)
                .addComponent(authPanel)
                .addSeparator()
                .addLabeledComponent("Account ID:", accountIdField)
                .addLabeledComponent("Email:", emailField)
                .addSeparator()
                .addLabeledComponent("Last quota response (JSON):", responseScroll)
                .getPanel();

        connection = ApplicationManager.getApplication().getMessageBus().connect();
        connection.subscribe(QuotaUsageListener.TOPIC, (QuotaUsageListener) (quota, error) -> {
            if (panel == null) {
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (panel == null || responseArea == null || accountIdField == null || emailField == null) {
                    return;
                }
                updateAccountFields();
                updateResponseArea();
            }, ModalityState.stateForComponent(panel));
        });

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public void apply() {
        // no-op
    }

    @Override
    public void reset() {
        updateAuthUi();
        updateAccountFields();
        updateResponseArea();
    }

    @Override
    public void disposeUIResources() {
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
        panel = null;
        accountIdField = null;
        emailField = null;
        responseArea = null;
        loginHeaderLabel = null;
        statusLabel = null;
        loginButton = null;
        cancelLoginButton = null;
        logoutButton = null;
    }

    private void updateAuthUi() {
        QuotaAuthService authService = QuotaAuthService.getInstance();
        boolean loggedIn = authService.isLoggedIn();
        boolean inProgress = authService.isLoginInProgress();
        String statusText = loggedIn ? "Logged in" : "Not logged in";
        if (loginHeaderLabel != null) {
            loginHeaderLabel.setText("Login (" + statusText + ")");
        }
        if (statusLabel != null) {
            statusLabel.setText(statusText);
        }
        loginButton.setEnabled(!inProgress && !loggedIn);
        cancelLoginButton.setEnabled(inProgress);
        logoutButton.setEnabled(loggedIn);
    }

    private void updateResponseArea() {
        if (responseArea == null) {
            return;
        }
        String json = QuotaUsageService.getInstance().getLastResponseJson();
        if (json == null || json.isBlank()) {
            responseArea.setText("No quota response yet.");
        } else {
            responseArea.setText(json);
        }
        responseArea.setCaretPosition(0);
    }

    private void updateAccountFields() {
        if (accountIdField == null || emailField == null) {
            return;
        }
        QuotaAuthService authService = QuotaAuthService.getInstance();
        String accountId = authService.getAccountId();
        String email = null;
        if (authService.isLoggedIn()) {
            de.moritzf.quota.OpenAiCodexQuota quota = QuotaUsageService.getInstance().getLastQuota();
            if (quota != null) {
                email = quota.getEmail();
            }
        }
        accountIdField.setText(accountId == null ? "" : accountId);
        emailField.setText(email == null ? "" : email);
    }
}
