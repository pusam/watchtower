package com.watchtower.config;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentSignatureFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void accepts_validSignature() throws Exception {
        byte[] body = "{\"hostId\":\"server-a\"}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis() / 1000L;
        String sig = sign(SECRET, "server-a", Long.toString(ts), body);

        MockHttpServletRequest req = req("server-a", Long.toString(ts), sig, null, body);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        newFilter().doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(req.getAttribute(AgentSignatureFilter.AUTHENTICATED_AGENT_ATTR))
                .isEqualTo("server-a");
    }

    @Test
    void rejects_invalidSignature() throws Exception {
        byte[] body = "{\"hostId\":\"server-a\"}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis() / 1000L;
        String bad = "deadbeef".repeat(8);

        MockHttpServletRequest req = req("server-a", Long.toString(ts), bad, null, body);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        newFilter().doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void rejects_staleTimestamp() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis() / 1000L - 3600;
        String sig = sign(SECRET, "server-a", Long.toString(ts), body);

        MockHttpServletRequest req = req("server-a", Long.toString(ts), sig, null, body);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        newFilter().doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void rejects_unknownAgent() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis() / 1000L;
        String sig = sign(SECRET, "someone-else", Long.toString(ts), body);

        MockHttpServletRequest req = req("someone-else", Long.toString(ts), sig, null, body);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        newFilter().doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void rejects_tamperedBody() throws Exception {
        byte[] signedBody = "{\"hostId\":\"server-a\"}".getBytes(StandardCharsets.UTF_8);
        byte[] sentBody = "{\"hostId\":\"attacker\"}".getBytes(StandardCharsets.UTF_8);
        long ts = System.currentTimeMillis() / 1000L;
        String sig = sign(SECRET, "server-a", Long.toString(ts), signedBody);

        MockHttpServletRequest req = req("server-a", Long.toString(ts), sig, null, sentBody);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        newFilter().doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void legacyKey_acceptedWhenAllowed() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest req = req(null, null, null, "legacy-api-key-abc-12345", body);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new AgentSignatureFilter(List.of(), "legacy-api-key-abc-12345", true, 300)
                .doFilter(req, resp, chain);

        verify(chain).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    void legacyKey_rejectedWhenDisabled() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest req = req(null, null, null, "legacy-api-key-abc-12345", body);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new AgentSignatureFilter(List.of(), "legacy-api-key-abc-12345", false, 300)
                .doFilter(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void canonical_format_isAgentNewlineTsNewlineBody() {
        byte[] out = AgentSignatureFilter.canonical(
                "agent-x", "1234567890",
                "body-content".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(out, StandardCharsets.UTF_8))
                .isEqualTo("agent-x\n1234567890\nbody-content");
    }

    private static AgentSignatureFilter newFilter() {
        MonitorProperties.AgentCredential c = new MonitorProperties.AgentCredential();
        c.setId("server-a");
        c.setHmacSecret(SECRET);
        return new AgentSignatureFilter(List.of(c), null, false, 300);
    }

    private static MockHttpServletRequest req(String agentId, String ts, String sig,
                                              String legacyKey, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/agent/report");
        if (agentId != null) req.addHeader(AgentSignatureFilter.AGENT_ID_HEADER, agentId);
        if (ts != null) req.addHeader(AgentSignatureFilter.TIMESTAMP_HEADER, ts);
        if (sig != null) req.addHeader(AgentSignatureFilter.SIGNATURE_HEADER, sig);
        if (legacyKey != null) req.addHeader(AgentSignatureFilter.LEGACY_API_KEY_HEADER, legacyKey);
        req.setContentType("application/json");
        req.setContent(body);
        return req;
    }

    private static String sign(String secret, String agentId, String ts, byte[] body) throws Exception {
        byte[] canonical = AgentSignatureFilter.canonical(agentId, ts, body);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical));
    }
}
