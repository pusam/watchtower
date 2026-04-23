package com.watchtower.collector;

/**
 * Best-effort redaction of literal values in SQL strings before they are stored
 * or displayed. Targets the most common secret-leak vectors:
 *
 * <ul>
 *   <li>Single-quoted string literals ({@code 'foo'} &rarr; {@code '?'})</li>
 *   <li>Long hex/numeric runs that could be tokens ({@code 0x..} / sequences &ge; 10 digits)</li>
 * </ul>
 *
 * Not a SQL parser — intentionally simple and fail-open; when in doubt we redact.
 */
final class SqlRedactor {

    private SqlRedactor() {}

    static String redact(String sql) {
        if (sql == null || sql.isEmpty()) return sql;
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append("'?'");
                i++;
                while (i < n) {
                    char x = sql.charAt(i);
                    if (x == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    if (x == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (x == '\'') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            // 0x... hex literal
            if ((c == '0') && i + 1 < n && (sql.charAt(i + 1) == 'x' || sql.charAt(i + 1) == 'X')
                    && (i == 0 || !isIdentPart(sql.charAt(i - 1)))) {
                out.append("?");
                i += 2;
                while (i < n && isHex(sql.charAt(i))) i++;
                continue;
            }
            // long digit runs (>=10): likely IDs, tokens, timestamps; redact
            if (Character.isDigit(c) && (i == 0 || !isIdentPart(sql.charAt(i - 1)))) {
                int j = i;
                while (j < n && (Character.isDigit(sql.charAt(j)) || sql.charAt(j) == '.')) j++;
                if (j - i >= 10) {
                    out.append("?");
                    i = j;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
