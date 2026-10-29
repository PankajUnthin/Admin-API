package com.iemr.admin.utils.nikshay;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.iemr.admin.config.NikshayProperties;
import com.iemr.admin.data.nikshay.dtos.NikshayApiResponse;
import com.iemr.admin.data.nikshay.dtos.NikshaySession;
import com.iemr.admin.data.nikshay.dtos.TransferPatientRequest;
import com.iemr.admin.exceptionhandler.NikshayApiException;
import com.iemr.admin.exceptionhandler.NikshaySessionExpiredException;
import com.iemr.admin.exceptionhandler.NikshayUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Calls Ni-kshay portal APIs with a logged-in session and parses the responses.
 * Sends the same headers the portal UI sends (Origin, Referer, X-Requested-With, Cookie).
 * Redirects are NOT followed: a redirect to SSO means the session expired.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NikshayApiClient {

    private static final int MAX_BODY_IN_MESSAGE = 300;

    private final NikshayProperties props;
    private final ObjectMapper objectMapper;

    /**
     * POST {origin}/Api/Patients/TransferPatient
     * (path and body from enrollment-flow.pdf – confirm once on the portal network tab).
     *
     * @return the parsed response when Success = true
     * @throws  401/403 or redirect to SSO → caller logs in again
     * @throws NikshayApiException            portal rejected the transfer (Success:false / 4xx)
     * @throws NikshayUnavailableException    timeout, I/O, 5xx
     */
    public NikshayApiResponse transferPatient(NikshaySession session, TransferPatientRequest request) {
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise TransferPatientRequest", e);
        }
        HttpRequest req = portalRequest(session, session.getOrigin() + "/Api/Patients/TransferPatient")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> res = send(req);
        guardSession(res);

        NikshayApiResponse body = parse(res);
        if (!body.isSuccess()) {
            String msg = body.errorText();
            throw new NikshayApiException("TransferPatient rejected: "
                    + (msg != null ? msg : "HTTP " + res.statusCode() + " " + shorten(res.body())), res.statusCode());
        }
        return body;
    }

    // ------------------------------------------------------------------ plumbing

    private HttpRequest.Builder portalRequest(NikshaySession s, String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(props.getHttp().getReadTimeoutMs()))
                .header("Accept", "*/*")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Origin", s.getOrigin())
                .header("Referer", s.getOrigin() + "/")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", props.getHttp().getUserAgent())
                .header("Cookie", s.getCookieHeader());
    }

    private HttpResponse<String> send(HttpRequest req) {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(props.getHttp().getConnectTimeoutMs()))
                .build();
        long t0 = System.currentTimeMillis();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("{} {} -> {} in {} ms", req.method(), req.uri().getPath(), r.statusCode(), System.currentTimeMillis() - t0);
            if (r.statusCode() >= 500) {
                throw new NikshayUnavailableException("Ni-kshay HTTP " + r.statusCode() + " on " + req.uri().getPath(), r.statusCode(), null);
            }
            return r;
        } catch (IOException e) {
            throw new NikshayUnavailableException("Ni-kshay unreachable: " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NikshayUnavailableException("Interrupted", null, e);
        }
    }

    private void guardSession(HttpResponse<String> r) {
        int sc = r.statusCode();
        if (sc == 401 || sc == 403) {
            throw new NikshaySessionExpiredException("Session rejected (HTTP " + sc + ")", sc);
        }
        if (sc >= 300 && sc < 400) {
            throw new NikshaySessionExpiredException("Redirected to " + r.headers().firstValue("Location").orElse("?"), sc);
        }
        if (sc >= 400) {
            throw new NikshayApiException("Ni-kshay HTTP " + sc + ": " + shorten(r.body()), sc);
        }
    }

    private NikshayApiResponse parse(HttpResponse<String> r) {
        try {
            return objectMapper.readValue(r.body(), NikshayApiResponse.class);
        } catch (Exception e) {
            throw new NikshayApiException("Unparseable Ni-kshay response (HTTP " + r.statusCode() + "): " + shorten(r.body()), r.statusCode());
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_BODY_IN_MESSAGE ? s : s.substring(0, MAX_BODY_IN_MESSAGE) + "…";
    }
}
