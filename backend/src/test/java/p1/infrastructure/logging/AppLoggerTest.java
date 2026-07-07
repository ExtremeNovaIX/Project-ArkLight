package p1.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppLoggerTest {

    @Test
    void shouldRenderStableKeyValueEventAndSkipBlankValues() {
        Logger logger = (Logger) LoggerFactory.getLogger("test.app-logger");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AppLogger appLogger = new AppLogger(logger);

            appLogger.info(LogDomain.GAME, "turn.completed", LogOutcome.SUCCEEDED,
                    Map.of("session", "qt session", "operations", 2, "empty", ""));

            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.INFO, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("domain=GAME"));
            assertTrue(message.contains("event=turn.completed"));
            assertTrue(message.contains("outcome=SUCCEEDED"));
            assertTrue(message.contains("session=\"qt session\""));
            assertTrue(message.contains("operations=2"));
            assertFalse(message.contains("empty="));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldRenderStructuredEventFromKeyValueArgumentsAndSkipNullValues() {
        Logger logger = (Logger) LoggerFactory.getLogger("test.app-logger-key-values");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            AppLogger appLogger = new AppLogger(logger);

            appLogger.info(LogDomain.MEMORY, "memory.compress.started", LogOutcome.SUCCEEDED,
                    "session", "qt session", "messageCount", 3, "ignored", null);

            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.INFO, event.getLevel());
            String message = event.getFormattedMessage();
            assertTrue(message.contains("domain=MEMORY"));
            assertTrue(message.contains("event=memory.compress.started"));
            assertTrue(message.contains("session=\"qt session\""));
            assertTrue(message.contains("messageCount=3"));
            assertFalse(message.contains("ignored="));
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            appender.stop();
        }
    }

    @Test
    void shouldRestoreMdcAfterScopedContext() {
        MDC.clear();
        MDC.put("traceId", "outer");

        try (LogContext ignored = LogContext.put("traceId", "inner", "domain", "GAME")) {
            assertEquals("inner", MDC.get("traceId"));
            assertEquals("GAME", MDC.get("domain"));
        }

        assertEquals("outer", MDC.get("traceId"));
        assertNull(MDC.get("domain"));
        MDC.clear();
    }
    @Test
    void shouldAttachThrowableToWarnEvent() {
        Logger logger = (Logger) LoggerFactory.getLogger("test.app-logger-warn");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            RuntimeException failure = new RuntimeException("fallback");
            AppLogger appLogger = new AppLogger(logger);

            appLogger.warn(LogDomain.MEMORY, "vector.rebuild_fallback", LogOutcome.DEGRADED,
                    Map.of("session", "default"), failure);

            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.WARN, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("domain=MEMORY"));
            assertTrue(event.getFormattedMessage().contains("event=vector.rebuild_fallback"));
            assertEquals("fallback", event.getThrowableProxy().getMessage());
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            appender.stop();
        }
    }

    @Test
    void shouldAttachThrowableToErrorEvent() {
        Logger logger = (Logger) LoggerFactory.getLogger("test.app-logger-error");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            IllegalArgumentException failure = new IllegalArgumentException("bad input");
            AppLogger appLogger = new AppLogger(logger);

            appLogger.error(LogDomain.RUNTIME, "runtime.failed", LogOutcome.FAILED,
                    Map.of("reason", failure.getMessage()), failure);

            ILoggingEvent event = appender.list.getFirst();
            assertEquals(Level.ERROR, event.getLevel());
            assertTrue(event.getFormattedMessage().contains("reason=\"bad input\""));
            assertEquals("bad input", event.getThrowableProxy().getMessage());
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            appender.stop();
        }
    }
}