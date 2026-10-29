package com.iemr.admin.service.nikshay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.iemr.admin.config.NikshayProperties;
import com.iemr.admin.data.nikshay.NikshayDeviceConfiguration;
import com.iemr.admin.data.nikshay.dtos.NikshaySession;
import com.iemr.admin.exceptionhandler.NikshayLoginException;
import com.iemr.admin.exceptionhandler.NikshayUnavailableException;
import com.iemr.admin.utils.nikshay.CredentialCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Ni-kshay SSO login for one device (wrapper PHI user), without a browser.
 * Port of nikshay-enroll/lib/auth.js → submitWithOwnHashing():
 *
 *   1  GET  {sso}/v1/sso/login?...            login page: clientId (+ captcha text if rendered) + cookies
 *   2  GET  {sso}/v1/ssoserver/client         X-SSO-Client-Id                → data.authToken
 *   3  GET  {sso}/v1/user/saltKey?username=   X-SSO-Client-Id, X-SSO-Auth-Token → data (salt)
 *   4  GET  {sso}/v1/ssoserver/client         fresh token
 *      GET  {sso}/v1/ssoserver/session-key    X-SSO-Client-Id, X-SSO-Auth-Token → data (session key)
 *   5  hash = sha256hex( sessionKey + sha256hex( salt + password ) )
 *   6  POST login form with password = hash, isEncryptedData=true   (field names: VERIFY)
 *   7  redirects followed → must land on {origin}/Home
 *   8  cookies for {origin} → NikshaySession.cookieHeader
 *   9  GET {origin}/Home with the cookie, redirects NOT followed → must not bounce to SSO
 *
 * Reusable for every device type (NAAT, XRAY): pass the device row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NikshayLoginService {

    private final NikshayProperties props;
    private final CredentialCipher cipher;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * Logs in with the device's wrapper credentials.
     * Network errors are retried sso.loginRetryCount times; rejected credentials are thrown at once.
     */
    public NikshaySession login(NikshayDeviceConfiguration device) {
        if (isBlank(device.getPhiUserId()) || isBlank(device.getPassword())) {
            throw new NikshayLoginException("Device " + device.getDeviceId() + " has no PHI login configured", null);
        }
        String password;
        try {
            password = cipher.decrypt(device.getPassword());
        } catch (IllegalStateException e) {
            throw new NikshayLoginException("Device " + device.getDeviceId() + ": " + e.getMessage(), null);
        }

        int attempts = Math.max(1, props.getSso().getLoginRetryCount() + 1);
        NikshayUnavailableException last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                NikshaySession s = doLogin(device, password);
                log.info("Ni-kshay login OK: device {} ({})", device.getDeviceId(), device.getPhiUserId());
                return s;
            } catch (NikshayUnavailableException e) {
                last = e;
                log.warn("Ni-kshay login attempt {}/{} for {} failed: {}", i, attempts, device.getPhiUserId(), e.getMessage());
                pause(1500L * i);
            }
        }
        throw last;
    }

    /** Best effort; the caller drops the session regardless. */
    public void logout(NikshaySession session) {
        if (session == null) {
            return;
        }
        try {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofMillis(props.getHttp().getConnectTimeoutMs()))
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.active().getSsoBase() + "/v1/sso/logout")) // VERIFY path
                    .timeout(Duration.ofMillis(props.getHttp().getReadTimeoutMs()))
                    .header("User-Agent", props.getHttp().getUserAgent())
                    .header("Cookie", session.getCookieHeader())
                    .GET().build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("Ni-kshay logout ignored for {}: {}", session.getPhiUserId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the 9 steps

    private NikshaySession doLogin(NikshayDeviceConfiguration device, String password) {
        NikshayProperties.Env env = props.active();
        NikshayProperties.Sso sso = props.getSso();

        // fresh cookie jar per login – never shared between devices
        CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .cookieHandler(jar)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(props.getHttp().getConnectTimeoutMs()))
                .build();

        // 1. login page
        String loginUrl = env.loginUrl();
        HttpResponse<String> page = send(http, base(loginUrl).GET());
        String clientId = firstGroup(page.body(), "name=\"" + Pattern.quote(sso.getClientIdField()) + "\"[^>]*value=\"([^\"]+)\"");
        if (clientId == null) {
            clientId = env.getClientId();
        }
        String captcha = firstGroup(page.body(), "id=\"capText\"[^>]*>\\s*([A-Za-z0-9]{4,})\\s*<");
        if (captcha == null) {
            captcha = randomCaptcha();
        }

        // 2. auth token
        String authToken = text(getJson(http, env.getSsoBase() + "/v1/ssoserver/client",
                Map.of("X-SSO-Client-Id", clientId)).path("data").path("authToken"));
        if (authToken == null) {
            throw new NikshayUnavailableException("SSO did not issue an auth token", null, null);
        }

        // 3. salt
        JsonNode salt = getJson(http, env.getSsoBase() + "/v1/user/saltKey?username=" + enc(device.getPhiUserId()),
                Map.of("X-SSO-Client-Id", clientId, "X-SSO-Auth-Token", authToken));
        if (!salt.path("success").asBoolean(false) || text(salt.path("data")) == null) {
            throw new NikshayLoginException("SSO returned no salt for user " + device.getPhiUserId() + " (unknown username?)", null);
        }

        // 4. session key (fresh token first, as the portal page does)
        String authToken2 = text(getJson(http, env.getSsoBase() + "/v1/ssoserver/client",
                Map.of("X-SSO-Client-Id", clientId)).path("data").path("authToken"));
        JsonNode sessionKey = getJson(http, env.getSsoBase() + "/v1/ssoserver/session-key",
                Map.of("X-SSO-Client-Id", clientId, "X-SSO-Auth-Token", authToken2 == null ? "" : authToken2));
        if (!sessionKey.path("success").asBoolean(false) || text(sessionKey.path("data")) == null) {
            throw new NikshayUnavailableException("SSO did not return a session key", null, null);
        }

        // 5. two-round SHA-256
        String digest = sha256Hex(sessionKey.path("data").asText() + sha256Hex(salt.path("data").asText() + password));

        // 6. login form POST                                   // VERIFY field names / action
        Map<String, String> form = new LinkedHashMap<>();
        form.put(sso.getUsernameField(), device.getPhiUserId());
        form.put(sso.getPasswordField(), digest);
        form.put(sso.getCaptchaField(), captcha);
        form.put(sso.getClientIdField(), clientId);
        form.put(sso.getEncryptedFlagField(), "true");
        form.put(sso.getExternalLoginField(), "false");
        form.put(sso.getReturnUrlField(), env.homeUrl());
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        String action = isBlank(sso.getFormAction()) ? loginUrl : sso.getFormAction();
        HttpResponse<String> post = send(http, base(action)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", env.getSsoBase())
                .header("Referer", loginUrl)
                .POST(HttpRequest.BodyPublishers.ofString(body)));

        // 7. landed on the portal?
        if (!post.uri().toString().startsWith(env.getOrigin())) {
            String err = firstGroup(post.body(), "id=\"setError\"[^>]*>\\s*([^<]{3,200})<");
            if (err == null) {
                err = firstGroup(post.body(), "(?i)((?:invalid|incorrect|wrong|failed)[^<]{0,120})");
            }
            throw new NikshayLoginException("Login rejected for " + device.getPhiUserId()
                    + (err != null ? ": " + err.trim() : ": did not reach " + env.homeUrl()), post.statusCode());
        }

        // 8. cookies for the portal origin
        List<HttpCookie> cookies = jar.getCookieStore().get(URI.create(env.getOrigin()));
        String cookieHeader = cookies.stream().map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
        if (cookieHeader.isEmpty()) {
            throw new NikshayLoginException("Login for " + device.getPhiUserId() + " returned no portal cookies", post.statusCode());
        }

        NikshaySession session = NikshaySession.builder()
                .deviceConfigId(device.getId())
                .deviceId(device.getDeviceId())
                .phiUserId(device.getPhiUserId())
                .origin(env.getOrigin())
                .cookieHeader(cookieHeader)
                .loggedInAt(Instant.now())
                .build();

        // 9. verify
        verify(session);
        return session;
    }

    private void verify(NikshaySession s) {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(props.getHttp().getConnectTimeoutMs()))
                .build();
        HttpResponse<String> r = send(http, base(s.getOrigin() + "/Home").header("Cookie", s.getCookieHeader()).GET());
        int sc = r.statusCode();
        if (sc == 401 || sc == 403 || (sc >= 300 && sc < 400)) {
            throw new NikshayLoginException("Session verification failed for " + s.getPhiUserId()
                    + " (HTTP " + sc + " " + r.headers().firstValue("Location").orElse("") + ")", sc);
        }
    }

    // ------------------------------------------------------------------ helpers

    private HttpRequest.Builder base(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.getHttp().getReadTimeoutMs()))
                .header("User-Agent", props.getHttp().getUserAgent());
    }

    private HttpResponse<String> send(HttpClient http, HttpRequest.Builder b) {
        HttpRequest req = b.build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 500) {
                throw new NikshayUnavailableException("SSO HTTP " + r.statusCode() + " on " + req.uri().getPath(), r.statusCode(), null);
            }
            return r;
        } catch (IOException e) {
            throw new NikshayUnavailableException("Ni-kshay SSO unreachable: " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NikshayUnavailableException("Interrupted during login", null, e);
        }
    }

    private JsonNode getJson(HttpClient http, String url, Map<String, String> headers) {
        HttpRequest.Builder b = base(url).header("Accept", "application/json").GET();
        headers.forEach(b::header);
        HttpResponse<String> r = send(http, b);
        try {
            return objectMapper.readTree(r.body());
        } catch (Exception e) {
            throw new NikshayUnavailableException("SSO returned non-JSON from " + URI.create(url).getPath(), r.statusCode(), e);
        }
    }

    private static String sha256Hex(String in) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomCaptcha() {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(a.charAt(random.nextInt(a.length())));
        }
        return sb.toString();
    }

    private static String firstGroup(String text, String regex) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String text(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
