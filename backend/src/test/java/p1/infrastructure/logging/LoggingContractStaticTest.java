package p1.infrastructure.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingContractStaticTest {
    private static final Pattern MAIN_LOG_CALL = Pattern.compile("log\\.(info|warn|error)\\s*\\(");
    private static final Pattern MECHANICAL_FIELD_NAME = Pattern.compile("\"(?:arg\\d+|sourceLine|value\\d+)\"");
    private static final Pattern GENERATED_EVENT_NAME = Pattern.compile("\"(?:[^\"]*\\.(?:info|warn|error)\\.\\d+|[^\"]*event_\\d+)\"");

    @Test
    void mainLogInfoWarnErrorMustUseStructuredDomain() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                Matcher matcher = MAIN_LOG_CALL.matcher(text);
                while (matcher.find()) {
                    String firstArgument = text.substring(matcher.end()).stripLeading();
                    if (firstArgument.startsWith("LogDomain.") || firstArgument.startsWith("loggedOperation.domain()")) {
                        continue;
                    }
                    int line = (int) text.substring(0, matcher.start()).chars().filter(ch -> ch == '\n').count() + 1;
                    violations.add(sourceRoot.relativize(file) + ":" + line + " -> " + matcher.group());
                }
            }
        }

        assertTrue(violations.isEmpty(), "Main log calls must be structured:\n" + String.join("\n", violations));
    }

    @Test
    void structuredLogFieldsMustNotUseMechanicalNames() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                Matcher matcher = MECHANICAL_FIELD_NAME.matcher(text);
                while (matcher.find()) {
                    int line = (int) text.substring(0, matcher.start()).chars().filter(ch -> ch == '\n').count() + 1;
                    violations.add(sourceRoot.relativize(file) + ":" + line + " -> " + matcher.group());
                }
            }
        }

        assertTrue(violations.isEmpty(), "Structured log fields must use domain names, not mechanical placeholders:\n" + String.join("\n", violations));
    }

    @Test
    void structuredLogEventsMustNotUseGeneratedNames() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                Matcher matcher = GENERATED_EVENT_NAME.matcher(text);
                while (matcher.find()) {
                    int line = (int) text.substring(0, matcher.start()).chars().filter(ch -> ch == '\n').count() + 1;
                    violations.add(sourceRoot.relativize(file) + ":" + line + " -> " + matcher.group());
                }
            }
        }

        assertTrue(violations.isEmpty(), "Structured log event names must be semantic, not generated from class and level:\n" + String.join("\n", violations));
    }
}
