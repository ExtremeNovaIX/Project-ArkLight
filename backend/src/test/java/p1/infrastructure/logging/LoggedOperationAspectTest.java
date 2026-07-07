package p1.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggedOperationAspectTest {

    @Test
    void shouldLogSuccessfulBoundaryWithMdcAndWithoutArguments() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggedOperationAspect.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            MDC.put("traceId", "trace-1");
            MDC.put("sessionId", "session-1");
            ProceedingJoinPoint joinPoint = joinPoint("startLoop", "secret prompt text", "ok");
            LoggedOperation operation = TestOperations.class
                    .getDeclaredMethod("startLoop", String.class)
                    .getAnnotation(LoggedOperation.class);

            Object result = new LoggedOperationAspect().around(joinPoint, operation);

            assertEquals("ok", result);
            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.INFO, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("domain=GAME"));
            assertTrue(message.contains("event=method.completed"));
            assertTrue(message.contains("outcome=SUCCEEDED"));
            assertTrue(message.contains("operation=game.loop.start"));
            assertTrue(message.contains("method=TestOperations.startLoop"));
            assertTrue(message.contains("traceId=trace-1"));
            assertTrue(message.contains("session=session-1"));
            assertTrue(message.contains("durationMs="));
            assertFalse(message.contains("secret prompt text"));
        } finally {
            MDC.clear();
            detach(logger, appender);
        }
    }

    @Test
    void shouldLogFailedBoundaryAndRethrowOriginalException() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggedOperationAspect.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        IllegalStateException failure = new IllegalStateException("state changed");
        try {
            ProceedingJoinPoint joinPoint = joinPoint("stopLoop", "ignored", failure);
            LoggedOperation operation = TestOperations.class
                    .getDeclaredMethod("stopLoop", String.class)
                    .getAnnotation(LoggedOperation.class);

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> new LoggedOperationAspect().around(joinPoint, operation));

            assertEquals(failure, thrown);
            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.ERROR, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("domain=GAME"));
            assertTrue(message.contains("event=method.failed"));
            assertTrue(message.contains("outcome=FAILED"));
            assertTrue(message.contains("operation=game.loop.stop"));
            assertTrue(message.contains("exception=IllegalStateException"));
            assertTrue(message.contains("reason=\"state changed\""));
        } finally {
            detach(logger, appender);
        }
    }

    @Test
    void shouldTreatErrorResponseEntityAsFailedBoundary() throws Throwable {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggedOperationAspect.class);
        ListAppender<ILoggingEvent> appender = attach(logger);
        try {
            ProceedingJoinPoint joinPoint = joinPoint("handledFailure", "ignored",
                    ResponseEntity.internalServerError().body("failed"));
            LoggedOperation operation = TestOperations.class
                    .getDeclaredMethod("handledFailure", String.class)
                    .getAnnotation(LoggedOperation.class);

            Object result = new LoggedOperationAspect().around(joinPoint, operation);

            assertTrue(result instanceof ResponseEntity<?>);
            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.WARN, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("event=method.failed"));
            assertTrue(message.contains("outcome=FAILED"));
            assertTrue(message.contains("operation=chat.send"));
            assertTrue(message.contains("httpStatus=500"));
        } finally {
            detach(logger, appender);
        }
    }
    private ProceedingJoinPoint joinPoint(String methodName, Object argument, Object resultOrFailure) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getDeclaringType()).thenReturn((Class) TestOperations.class);
        when(signature.getDeclaringTypeName()).thenReturn(TestOperations.class.getName());
        when(signature.getName()).thenReturn(methodName);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{argument});
        if (resultOrFailure instanceof Throwable throwable) {
            when(joinPoint.proceed()).thenThrow(throwable);
        } else {
            when(joinPoint.proceed()).thenReturn(resultOrFailure);
        }
        return joinPoint;
    }

    private ListAppender<ILoggingEvent> attach(Logger logger) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        return appender;
    }

    private void detach(Logger logger, ListAppender<ILoggingEvent> appender) {
        logger.detachAppender(appender);
        logger.setAdditive(true);
        appender.stop();
    }

    static class TestOperations {
        @LoggedOperation(domain = LogDomain.HTTP, operation = "chat.send")
        ResponseEntity<String> handledFailure(String input) {
            return ResponseEntity.internalServerError().body("failed");
        }
        @LoggedOperation(domain = LogDomain.GAME, operation = "game.loop.start")
        String startLoop(String input) {
            return "ok";
        }

        @LoggedOperation(domain = LogDomain.GAME, operation = "game.loop.stop")
        String stopLoop(String input) {
            return "never";
        }
    }
}