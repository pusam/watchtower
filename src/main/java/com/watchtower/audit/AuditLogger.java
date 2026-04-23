package com.watchtower.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emits structured audit events for security-relevant admin actions.
 *
 * Output goes to the dedicated "watchtower.audit" logger so it can be routed
 * to a separate file/collector without the rest of the app log.
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger("watchtower.audit");

    public void record(String action, String user, String ip, Map<String, ?> details) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("action=").append(safe(action));
        sb.append(" user=").append(safe(user));
        sb.append(" ip=").append(safe(ip));
        if (details != null) {
            for (Map.Entry<String, ?> e : details.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(safe(String.valueOf(e.getValue())));
            }
        }
        log.info(sb.toString());
    }

    public void record(String action, String user, String ip) {
        record(action, user, ip, Map.of());
    }

    public static Map<String, Object> details() {
        return new LinkedHashMap<>();
    }

    private static String safe(String s) {
        if (s == null) return "-";
        // Quote if contains spaces, =, or newlines; escape embedded quotes.
        if (s.indexOf(' ') < 0 && s.indexOf('=') < 0 && s.indexOf('\n') < 0 && s.indexOf('"') < 0) {
            return s;
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
