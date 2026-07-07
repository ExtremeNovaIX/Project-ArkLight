package p1.infrastructure.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class AppLogger {
    private final Logger logger;

    public AppLogger(Logger logger) {
        this.logger = logger;
    }

    public static AppLogger getLogger(Class<?> type) {
        return new AppLogger(LoggerFactory.getLogger(type));
    }

    public void trace(String message, Object... arguments) {
        logger.trace(message, arguments);
    }

    public void debug(LogDomain domain, String event, LogOutcome outcome, Map<String, ?> fields) {
        logger.debug(format(domain, event, outcome, fields));
    }

    public void debug(String message, Object... arguments) {
        logger.debug(message, arguments);
    }

    public void info(String message, Object... arguments) {
        logger.info(message, arguments);
    }

    public void infoBlock(String message) {
        logger.info(message);
    }

    public void info(LogDomain domain, String event, LogOutcome outcome) {
        logger.info(format(domain, event, outcome, Map.of()));
    }

    public void info(LogDomain domain, String event, LogOutcome outcome, Map<String, ?> fields) {
        logger.info(format(domain, event, outcome, fields));
    }

    public void info(LogDomain domain, String event, LogOutcome outcome, Object... fields) {
        logger.info(format(domain, event, outcome, fields(fields)));
    }

    public void warn(String message, Object... arguments) {
        logger.warn(message, arguments);
    }

    public void warn(LogDomain domain, String event, LogOutcome outcome) {
        logger.warn(format(domain, event, outcome, Map.of()));
    }

    public void warn(LogDomain domain, String event, LogOutcome outcome, Map<String, ?> fields) {
        logger.warn(format(domain, event, outcome, fields));
    }

    public void warn(LogDomain domain, String event, LogOutcome outcome, Object... fields) {
        logger.warn(format(domain, event, outcome, fields(fields)));
    }

    public void warn(LogDomain domain, String event, LogOutcome outcome, Map<String, ?> fields, Throwable throwable) {
        logger.warn(format(domain, event, outcome, fields), throwable);
    }

    public void warn(LogDomain domain, String event, LogOutcome outcome, Throwable throwable) {
        logger.warn(format(domain, event, outcome, Map.of()), throwable);
    }

    public void warn(LogDomain domain, String event, LogOutcome outcome, Throwable throwable, Object... fields) {
        logger.warn(format(domain, event, outcome, fields(fields)), throwable);
    }

    public void error(String message, Object... arguments) {
        logger.error(message, arguments);
    }

    public void error(LogDomain domain, String event, LogOutcome outcome) {
        logger.error(format(domain, event, outcome, Map.of()));
    }

    public void error(LogDomain domain, String event, LogOutcome outcome, Map<String, ?> fields) {
        logger.error(format(domain, event, outcome, fields));
    }

    public void error(LogDomain domain, String event, LogOutcome outcome, Object... fields) {
        logger.error(format(domain, event, outcome, fields(fields)));
    }

    public void error(LogDomain domain, String event, LogOutcome outcome, Map<String, ?> fields, Throwable throwable) {
        logger.error(format(domain, event, outcome, fields), throwable);
    }

    public void error(LogDomain domain, String event, LogOutcome outcome, Throwable throwable) {
        logger.error(format(domain, event, outcome, Map.of()), throwable);
    }

    public void error(LogDomain domain, String event, LogOutcome outcome, Throwable throwable, Object... fields) {
        logger.error(format(domain, event, outcome, fields(fields)), throwable);
    }

    public String format(LogDomain domain, String event, LogOutcome outcome, Map<String, ?> fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("domain", domain);
        values.put("event", event);
        values.put("outcome", outcome);
        if (fields != null) {
            values.putAll(fields);
        }
        StringBuilder sb = new StringBuilder();
        values.forEach((key, value) -> appendField(sb, key, value));
        return sb.toString();
    }

    private Map<String, ?> fields(Object... keyValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (keyValues == null) {
            return fields;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String fieldName && !fieldName.isBlank()) {
                fields.put(fieldName, keyValues[i + 1]);
            }
        }
        return fields;
    }
    private void appendField(StringBuilder sb, String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        String rendered = String.valueOf(value);
        if (rendered.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(key).append('=').append(renderValue(rendered));
    }

    private String renderValue(String value) {
        if (value.chars().anyMatch(Character::isWhitespace) || value.contains("\"") || value.contains("\\")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }
}