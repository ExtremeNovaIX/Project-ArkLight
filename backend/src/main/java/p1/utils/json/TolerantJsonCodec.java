package p1.utils.json;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.internal.Json;
import lombok.CustomLog;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import p1.utils.json.TolerantJsonFieldRepairer.RepairReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@CustomLog
public class TolerantJsonCodec implements Json.JsonCodec {

    private static final List<String> REPAIR_PACKAGE_PREFIXES = List.of(
            "p1.model",
            "p1.component.agent.memory.FactExtractionService"
    );

    private static final List<String> JSON_KEYWORDS = List.of("true", "false", "null");

    private final Object delegate;
    private final Method toJsonMethod;
    private final Method fromJsonClassMethod;
    private final Method fromJsonTypeMethod;
    private final ObjectMapper objectMapper;
    private final TolerantJsonFieldRepairer fieldRepairer;

    public TolerantJsonCodec() {
        try {
            Class<?> codecClass = Class.forName("dev.langchain4j.internal.JacksonJsonCodec");
            Constructor<?> constructor = codecClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            this.delegate = constructor.newInstance();

            this.toJsonMethod = codecClass.getDeclaredMethod("toJson", Object.class);
            this.fromJsonClassMethod = codecClass.getDeclaredMethod("fromJson", String.class, Class.class);
            this.fromJsonTypeMethod = codecClass.getDeclaredMethod("fromJson", String.class, Type.class);
            Method objectMapperMethod = codecClass.getDeclaredMethod("getObjectMapper");

            this.toJsonMethod.setAccessible(true);
            this.fromJsonClassMethod.setAccessible(true);
            this.fromJsonTypeMethod.setAccessible(true);
            objectMapperMethod.setAccessible(true);

            this.objectMapper = (ObjectMapper) objectMapperMethod.invoke(delegate);
            this.fieldRepairer = new TolerantJsonFieldRepairer(objectMapper);
            log.info(LogDomain.RUNTIME, "json.codec_initialized", LogOutcome.SUCCEEDED);
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize tolerant json codec", e);
        }
    }

    @Override
    public String toJson(Object object) {
        try {
            return (String) toJsonMethod.invoke(delegate, object);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize json", e);
        }
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        JavaType targetType = objectMapper.constructType(type);
        try {
            @SuppressWarnings("unchecked")
            T result = (T) fromJsonClassMethod.invoke(delegate, json, type);
            return result;
        } catch (Exception e) {
            String sanitizedJson = sanitizeJsonText(json);
            if (!sanitizedJson.equals(json)) {
                try {
                    @SuppressWarnings("unchecked")
                    T result = (T) fromJsonClassMethod.invoke(delegate, sanitizedJson, type);
                    log.info(LogDomain.RUNTIME, "json.sanitized_parse_succeeded", LogOutcome.SUCCEEDED, fields(targetType));
                    return result;
                } catch (Exception sanitizedException) {
                    e.addSuppressed(sanitizedException);
                    if (!shouldRepair(targetType)) {
                        throw propagateOriginal(sanitizedException);
                    }
                    return repairAndRead(sanitizedJson, targetType, sanitizedException);
                }
            }
            if (!shouldRepair(targetType)) {
                throw propagateOriginal(e);
            }
            return repairAndRead(json, targetType, e);
        }
    }

    @Override
    public <T> T fromJson(String json, Type type) {
        JavaType targetType = objectMapper.constructType(type);
        try {
            @SuppressWarnings("unchecked")
            T result = (T) fromJsonTypeMethod.invoke(delegate, json, type);
            return result;
        } catch (Exception e) {
            String sanitizedJson = sanitizeJsonText(json);
            if (!sanitizedJson.equals(json)) {
                try {
                    @SuppressWarnings("unchecked")
                    T result = (T) fromJsonTypeMethod.invoke(delegate, sanitizedJson, type);
                    log.info(LogDomain.RUNTIME, "json.sanitized_parse_succeeded", LogOutcome.SUCCEEDED, fields(targetType));
                    return result;
                } catch (Exception sanitizedException) {
                    e.addSuppressed(sanitizedException);
                    if (!shouldRepair(targetType)) {
                        throw propagateOriginal(sanitizedException);
                    }
                    return repairAndRead(sanitizedJson, targetType, sanitizedException);
                }
            }
            if (!shouldRepair(targetType)) {
                throw propagateOriginal(e);
            }
            return repairAndRead(json, targetType, e);
        }
    }

    private <T> T repairAndRead(String json, JavaType targetType, Exception originalException) {
        try {
            log.warn(LogDomain.RUNTIME, "json.default_deserialization_failed", LogOutcome.DEGRADED,
                    fields(targetType, "reason", rootMessage(originalException)));
            JsonNode originalTree = objectMapper.readTree(json);
            RepairReport report = fieldRepairer.repair(originalTree, targetType);
            JsonNode repairedTree = report.repairedTree();
            T result = objectMapper.readerFor(targetType).readValue(repairedTree);

            if (report.changed()) {
                log.info(LogDomain.RUNTIME, "json.repair_succeeded", LogOutcome.SUCCEEDED, fields(targetType, "changes", report.describeChanges()));
            } else {
                log.info(LogDomain.RUNTIME, "json.repair_skipped", LogOutcome.SKIPPED, fields(targetType, "reason", "no_field_changes"));
            }
            return result;
        } catch (Exception repairException) {
            log.error(LogDomain.RUNTIME, "json.repair_failed", LogOutcome.FAILED,
                    fields(targetType, "originalReason", rootMessage(originalException),
                            "repairReason", rootMessage(repairException)), repairException);
            originalException.addSuppressed(repairException);
            throw propagateOriginal(originalException);
        }
    }
    private java.util.Map<String, Object> fields(JavaType targetType, Object... keyValues) {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("targetType", targetType);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return fields;
    }

    private boolean shouldRepair(JavaType targetType) {
        if (targetType == null) {
            return false;
        }
        if (isWhitelisted(targetType.getRawClass())) {
            return true;
        }
        if (targetType.hasContentType() && shouldRepair(targetType.getContentType())) {
            return true;
        }
        if (targetType.getKeyType() != null && shouldRepair(targetType.getKeyType())) {
            return true;
        }
        return false;
    }

    private boolean isWhitelisted(Class<?> rawClass) {
        if (rawClass == null) {
            return false;
        }
        String className = rawClass.getName();
        return REPAIR_PACKAGE_PREFIXES.stream().anyMatch(className::startsWith);
    }

    private String sanitizeJsonText(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return rawJson;
        }

        String text = stripMarkdownFence(rawJson.trim());
        if (looksLikeWholeJsonValue(text)) {
            return repairInterleavedNoise(text);
        }

        String extractedJson = extractFirstValidJsonSegment(text);
        if (extractedJson != null) {
            return extractedJson;
        }

        return repairInterleavedNoise(text);
    }

    private String stripMarkdownFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }

        int firstLineBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstLineBreak >= 0 && lastFence > firstLineBreak) {
            return text.substring(firstLineBreak + 1, lastFence).trim();
        }
        return text;
    }

    private boolean looksLikeWholeJsonValue(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        return (first == '{' && last == '}')
                || (first == '[' && last == ']');
    }

    private String extractFirstValidJsonSegment(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') {
                continue;
            }

            String candidate = extractBalancedJsonValue(text, i);
            if (candidate == null) {
                continue;
            }

            String parseableCandidate = parseableCandidate(candidate);
            if (parseableCandidate != null) {
                return parseableCandidate;
            }
        }
        return null;
    }

    private String parseableCandidate(String candidate) {
        if (isValidJson(candidate)) {
            return candidate;
        }

        String repairedCandidate = repairInterleavedNoise(candidate);
        if (!repairedCandidate.equals(candidate) && isValidJson(repairedCandidate)) {
            return repairedCandidate;
        }
        return null;
    }

    private boolean isValidJson(String text) {
        try {
            objectMapper.readTree(text);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String repairInterleavedNoise(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        StringBuilder repaired = new StringBuilder(text.length());
        boolean changed = false;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);

            if (inString) {
                repaired.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                i++;
                continue;
            }

            if (c == '"') {
                repaired.append(c);
                inString = true;
                i++;
                continue;
            }

            if (Character.isWhitespace(c) || isStructuralCharacter(c)) {
                repaired.append(c);
                i++;
                continue;
            }

            int numberEnd = consumeJsonNumber(text, i);
            if (numberEnd > i) {
                repaired.append(text, i, numberEnd);
                i = numberEnd;
                continue;
            }

            String keyword = consumeJsonKeyword(text, i);
            if (keyword != null) {
                repaired.append(keyword);
                i += keyword.length();
                continue;
            }

            changed = true;
            i++;
        }

        return changed ? repaired.toString() : text;
    }

    private boolean isStructuralCharacter(char c) {
        return c == '{'
                || c == '}'
                || c == '['
                || c == ']'
                || c == ':'
                || c == ',';
    }

    private int consumeJsonNumber(String text, int start) {
        int index = start;
        if (text.charAt(index) == '-') {
            index++;
        }
        if (index >= text.length() || !Character.isDigit(text.charAt(index))) {
            return start;
        }

        if (text.charAt(index) == '0') {
            index++;
        } else {
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }

        if (index < text.length() && text.charAt(index) == '.') {
            int fractionStart = ++index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (fractionStart == index) {
                return start;
            }
        }

        if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            int exponentStart = index++;
            if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                index++;
            }
            int digitsStart = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (digitsStart == index) {
                return exponentStart;
            }
        }

        return index;
    }

    private String consumeJsonKeyword(String text, int start) {
        for (String keyword : JSON_KEYWORDS) {
            if (text.startsWith(keyword, start) && isKeywordBoundary(text, start + keyword.length())) {
                return keyword;
            }
        }
        return null;
    }

    private boolean isKeywordBoundary(String text, int index) {
        return index >= text.length()
                || Character.isWhitespace(text.charAt(index))
                || isStructuralCharacter(text.charAt(index));
    }

    private String extractBalancedJsonValue(String text, int start) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{' || c == '[') {
                stack.push(c);
                continue;
            }

            if (c != '}' && c != ']') {
                continue;
            }

            if (stack.isEmpty() || !isMatchingPair(stack.peek(), c)) {
                return null;
            }

            stack.pop();
            if (stack.isEmpty()) {
                return text.substring(start, i + 1).trim();
            }
        }
        return null;
    }

    private boolean isMatchingPair(char open, char close) {
        return (open == '{' && close == '}')
                || (open == '[' && close == ']');
    }

    private RuntimeException propagateOriginal(Exception exception) {
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException("failed to deserialize json", exception);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }
}
