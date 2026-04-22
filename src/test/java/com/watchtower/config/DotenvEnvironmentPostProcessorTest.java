package com.watchtower.config;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DotenvEnvironmentPostProcessorTest {

    @Test
    void parsesBasicLines() {
        Map<String, Object> out = DotenvEnvironmentPostProcessor.parse(List.of(
                "FOO=bar",
                "BAZ=qux"
        ));
        assertThat(out).containsEntry("FOO", "bar").containsEntry("BAZ", "qux");
    }

    @Test
    void skipsCommentsAndBlankLines() {
        Map<String, Object> out = DotenvEnvironmentPostProcessor.parse(List.of(
                "",
                "# this is a comment",
                "FOO=bar",
                "   ",
                "# FOO=ignored"
        ));
        assertThat(out).hasSize(1).containsEntry("FOO", "bar");
    }

    @Test
    void stripsMatchingQuotes() {
        Map<String, Object> out = DotenvEnvironmentPostProcessor.parse(List.of(
                "A=\"quoted value\"",
                "B='single'",
                "C=bare"
        ));
        assertThat(out)
                .containsEntry("A", "quoted value")
                .containsEntry("B", "single")
                .containsEntry("C", "bare");
    }

    @Test
    void handlesExportPrefix() {
        Map<String, Object> out = DotenvEnvironmentPostProcessor.parse(List.of(
                "export FOO=bar"
        ));
        assertThat(out).containsEntry("FOO", "bar");
    }

    @Test
    void ignoresMalformedLines() {
        Map<String, Object> out = DotenvEnvironmentPostProcessor.parse(List.of(
                "no-equals-sign",
                "=noKey",
                "GOOD=value"
        ));
        assertThat(out).hasSize(1).containsEntry("GOOD", "value");
    }

    @Test
    void preservesEqualsInValue() {
        Map<String, Object> out = DotenvEnvironmentPostProcessor.parse(List.of(
                "URL=https://example.com/path?q=1"
        ));
        assertThat(out).containsEntry("URL", "https://example.com/path?q=1");
    }
}
