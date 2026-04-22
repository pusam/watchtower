package com.watchtower.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Loads `.env` (KEY=VALUE, one per line) from the working directory at startup as a
 * low-priority property source. Real environment variables, system properties, and
 * command-line args still win — .env is only a fallback for local development.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "dotenv-local";
    private static final String FILE_NAME = ".env";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dotenv = Path.of(FILE_NAME);
        if (!Files.isRegularFile(dotenv)) return;

        Map<String, Object> values;
        try {
            values = parse(Files.readAllLines(dotenv, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[dotenv] failed to read " + FILE_NAME + ": " + e.getMessage());
            return;
        }
        if (values.isEmpty()) return;

        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, values));
        System.out.println("[dotenv] loaded " + values.size() + " key(s) from " + FILE_NAME);
    }

    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring("export ".length()).trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2
                    && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                    || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
                value = value.substring(1, value.length() - 1);
            }
            if (key.isEmpty()) continue;
            out.put(key, value);
        }
        return out;
    }
}
