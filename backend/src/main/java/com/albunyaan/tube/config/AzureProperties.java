package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Plan F (ADMIN-USER-01) — Azure AD app credentials for Microsoft Graph.
 */
@ConfigurationProperties(prefix = "azure")
public class AzureProperties {
    private String tenantId;
    private String clientId;
    private String clientSecret;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
}
