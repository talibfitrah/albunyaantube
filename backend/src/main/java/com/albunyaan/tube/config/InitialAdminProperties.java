package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.initial-admin")
public class InitialAdminProperties {

    private String email = "admin@albunyaan.tube";
    private String password = "ChangeMe!123";
    private String displayName = "Initial Admin";
    private boolean resetPasswordOnStartup = true;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isResetPasswordOnStartup() {
        return resetPasswordOnStartup;
    }

    public void setResetPasswordOnStartup(boolean resetPasswordOnStartup) {
        this.resetPasswordOnStartup = resetPasswordOnStartup;
    }
}
