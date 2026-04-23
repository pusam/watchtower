package com.watchtower.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Authenticates agent reports via per-agent HMAC-SHA256 signatures.
 *
 * <p>Canonical string: {@code agentId + "\n" + timestamp + "\n" + body} — signed with the
 * agent's {@code hmac-secret}. Requests older than {@code maxClockSkewSeconds} are rejected.
 *
 * <p>If no per-agent credentials are configured and {@code allowLegacyApiKey} is true, falls back
 * to single-key {@code X-API-Key} comparison for migration.
 */
public class AgentSignatureFilter extends OncePerRequestFilter {

    public static final String AGENT_ID_HEADER = "X-Agent-Id";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Signature";
    public static final String LEGACY_API_KEY_HEADER = "X-API-Key";
    public static final String AUTHENTICATED_AGENT_ATTR = "watchtower.authenticatedAgentId";
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024; // 4MB

    private final Map<String, List<byte[]>> agentSecrets;
    private final byte[] legacyKeyBytes;
    private final boolean allowLegacyKey;
    private final long maxSkewSeconds;

    public AgentSignatureFilter(List<MonitorProperties.AgentCredential> agents,
                                String legacyApiKey,
                                boolean allowLegacyApiKey,
                                long maxSkewSeconds) {
        Map<String, List<byte[]>> m = new ConcurrentHashMap<>();
        if (agents != null) {
            for (MonitorProperties.AgentCredential a : agents) {
                if (a.getId() == null) continue;
                java.util.ArrayList<byte[]> secrets = new java.util.ArrayList<>(2);
                if (a.getHmacSecret() != null && !a.getHmacSecret().isBlank()) {
                    secrets.add(a.getHmacSecret().getBytes(StandardCharsets.UTF_8));
                }
                if (a.getPreviousHmacSecrets() != null) {
                    for (String prev : a.getPreviousHmacSecrets()) {
                        if (prev != null && !prev.isBlank()) {
                            secrets.add(prev.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
                if (!secrets.isEmpty()) m.put(a.getId(), secrets);
            }
        }
        this.agentSecrets = m;
        this.legacyKeyBytes = (legacyApiKey == null || legacyApiKey.isBlank())
                ? null : legacyApiKey.getBytes(StandardCharsets.UTF_8);
        this.allowLegacyKey = allowLegacyApiKey && this.legacyKeyBytes != null;
        this.maxSkewSeconds = maxSkewSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"payload too large\"}");
            return;
        }
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        byte[] body = readBoundedBody(wrapped);
        if (body == null) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"payload too large\"}");
            return;
        }

        String agentId = request.getHeader(AGENT_ID_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);

        if (agentId != null && timestamp != null && signature != null) {
            String verified = verifySignature(agentId, timestamp, signature, body);
            if (verified == null) {
                deny(response, "Invalid signature, unknown agent, or clock drift");
                return;
            }
            authenticate(verified, request);
            filterChain.doFilter(wrapped, response);
            return;
        }

        // Legacy fallback
        if (allowLegacyKey) {
            String provided = request.getHeader(LEGACY_API_KEY_HEADER);
            if (provided != null
                    && MessageDigest.isEqual(legacyKeyBytes,
                            provided.getBytes(StandardCharsets.UTF_8))) {
                authenticate("legacy", request);
                filterChain.doFilter(wrapped, response);
                return;
            }
        }

        deny(response, "Missing or invalid agent credentials");
    }

    private String verifySignature(String agentId, String timestamp, String signatureHex, byte[] body) {
        List<byte[]> secrets = agentSecrets.get(agentId);
        if (secrets == null || secrets.isEmpty()) return null;

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > maxSkewSeconds) return null;

        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHex.toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] canonical = canonical(agentId, timestamp, body);
        for (byte[] secret : secrets) {
            byte[] expected = hmacSha256(secret, canonical);
            if (MessageDigest.isEqual(expected, provided)) return agentId;
        }
        return null;
    }

    static byte[] canonical(String agentId, String timestamp, byte[] body) {
        byte[] idBytes = agentId.getBytes(StandardCharsets.UTF_8);
        byte[] tsBytes = timestamp.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[idBytes.length + 1 + tsBytes.length + 1 + body.length];
        int p = 0;
        System.arraycopy(idBytes, 0, out, p, idBytes.length); p += idBytes.length;
        out[p++] = '\n';
        System.arraycopy(tsBytes, 0, out, p, tsBytes.length); p += tsBytes.length;
        out[p++] = '\n';
        System.arraycopy(body, 0, out, p, body.length);
        return out;
    }

    static byte[] hmacSha256(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private void authenticate(String principal, HttpServletRequest request) {
        request.setAttribute(AUTHENTICATED_AGENT_ATTR, principal);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
    }

    private void deny(HttpServletResponse response, String msg) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + msg + "\"}");
    }

    private static byte[] readBoundedBody(ContentCachingRequestWrapper wrapped) throws IOException {
        java.io.InputStream in = wrapped.getInputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        while (true) {
            int n = in.read(buf);
            if (n < 0) break;
            total += n;
            if (total > MAX_BODY_BYTES) return null;
        }
        return wrapped.getContentAsByteArray();
    }
}
