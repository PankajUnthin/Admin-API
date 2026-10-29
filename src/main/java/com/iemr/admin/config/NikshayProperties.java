package com.iemr.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** All nikshay.* properties (see application-nikshay.properties). */
@Data
@Configuration
@ConfigurationProperties(prefix = "nikshay")
public class NikshayProperties {

    /** uat | production */
    private String env = "uat";
    private Env uat = new Env();
    private Env production = new Env();
    private Http http = new Http();
    private Sso sso = new Sso();
    private Credential credential = new Credential();
    private Transfer transfer = new Transfer();

    public Env active() {
        return "production".equalsIgnoreCase(env) ? production : uat;
    }

    @Data
    public static class Env {
        private String ssoBase;
        private String origin;
        private String clientId;

        public String loginUrl() {
            return ssoBase + "/v1/sso/login?returnUrl=" + origin + "/Home&clientId=" + clientId;
        }

        public String homeUrl() {
            return origin + "/Home";
        }
    }

    @Data
    public static class Http {
        private int connectTimeoutMs = 15_000;
        private int readTimeoutMs = 60_000;
        private String userAgent;
    }

    /** SSO login form field names – VERIFY against one captured UAT login. */
    @Data
    public static class Sso {
        private String usernameField = "username";
        private String passwordField = "password";
        private String captchaField = "captcha";
        private String clientIdField = "clientId";
        private String encryptedFlagField = "isEncryptedData";
        private String externalLoginField = "isExternalLogin";
        private String returnUrlField = "returnUrl";
        /** empty = POST to the login page URL */
        private String formAction = "";
        private int loginRetryCount = 2;
    }

    @Data
    public static class Credential {
        private String keyBase64;
    }

    @Data
    public static class Transfer {
        private int batchSize = 200;
        private int maxAttempts = 3;
        private int callDelayMs = 500;
        private String reason = "Transfer to catchment PHI";
        private String naatPositiveRegex;
        private String naatNegativeRegex;
        private String xrayPositiveRegex;
        private String xrayNegativeRegex;
    }
}
