package p1.infrastructure.logging;

import lombok.CustomLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Aspect
@Component
@CustomLog
public class LoggedOperationAspect {

    @Around("@annotation(loggedOperation)")
    public Object around(ProceedingJoinPoint joinPoint, LoggedOperation loggedOperation) throws Throwable {
        long started = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            Map<String, Object> fields = fields(joinPoint, loggedOperation, started, null);
            if (result instanceof ResponseEntity<?> response && response.getStatusCode().isError()) {
                fields.put("httpStatus", response.getStatusCode().value());
                fields.put("reason", "http_error_status");
                log.warn(loggedOperation.domain(), "method.failed", LogOutcome.FAILED, fields);
            } else {
                log.info(loggedOperation.domain(), "method.completed", LogOutcome.SUCCEEDED, fields);
            }
            return result;
        } catch (Throwable throwable) {
            Map<String, Object> fields = fields(joinPoint, loggedOperation, started, throwable);
            log.error(loggedOperation.domain(), "method.failed", LogOutcome.FAILED, fields, throwable);
            throw throwable;
        }
    }

    private Map<String, Object> fields(ProceedingJoinPoint joinPoint,
                                       LoggedOperation loggedOperation,
                                       long started,
                                       Throwable throwable) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operation", loggedOperation.operation());
        fields.put("method", methodName(joinPoint));
        putMdc(fields, "traceId", "traceId");
        putMdc(fields, "sessionId", "session");
        putMdc(fields, "rpSessionId", "rpSession");
        putMdc(fields, "game", "game");
        putMdc(fields, "serviceInfo", "serviceInfo");
        if (throwable != null) {
            fields.put("exception", throwable.getClass().getSimpleName());
            fields.put("reason", throwable.getMessage());
        }
        fields.put("durationMs", elapsedMs(started));
        return fields;
    }

    private void putMdc(Map<String, Object> fields, String mdcKey, String fieldKey) {
        String value = MDC.get(mdcKey);
        if (value != null && !value.isBlank()) {
            fields.put(fieldKey, value);
        }
    }

    private String methodName(ProceedingJoinPoint joinPoint) {
        if (joinPoint.getSignature() instanceof MethodSignature methodSignature) {
            Class<?> declaringType = methodSignature.getDeclaringType();
            String typeName = declaringType == null
                    ? methodSignature.getDeclaringTypeName()
                    : declaringType.getSimpleName();
            return typeName + "." + methodSignature.getName();
        }
        return joinPoint.getSignature().toShortString();
    }

    private long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}