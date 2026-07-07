package p1.infrastructure.logging;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class LogContext implements AutoCloseable {
    private final Map<String, String> previousValues = new LinkedHashMap<>();
    private final Map<String, Boolean> existed = new LinkedHashMap<>();

    private LogContext(Map<String, String> values) {
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                return;
            }
            existed.put(key, MDC.get(key) != null);
            previousValues.put(key, MDC.get(key));
            MDC.put(key, value);
        });
    }

    public static LogContext put(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return new LogContext(Map.of());
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("LogContext keyValues must be even length");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return new LogContext(values);
    }

    public static String ensureTraceId() {
        String existing = MDC.get("traceId");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MDC.put("traceId", traceId);
        return traceId;
    }

    @Override
    public void close() {
        previousValues.forEach((key, previous) -> {
            if (Boolean.TRUE.equals(existed.get(key))) {
                MDC.put(key, previous);
            } else {
                MDC.remove(key);
            }
        });
    }
}