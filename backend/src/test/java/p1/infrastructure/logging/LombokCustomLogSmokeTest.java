package p1.infrastructure.logging;

import ch.qos.logback.classic.Level;
import lombok.CustomLog;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@CustomLog
class LombokCustomLogSmokeTest {

    @Test
    void shouldGenerateAppLoggerFieldNamedLog() {
        String rendered = log.format(LogDomain.GAME, "custom.log.smoke", LogOutcome.SUCCEEDED,
                Map.of("session", "default"));

        assertTrue(rendered.contains("domain=GAME"));
        assertTrue(rendered.contains("event=custom.log.smoke"));
        assertTrue(rendered.contains("session=default"));
    }

    @Test
    void shouldAcceptSlf4jStyleMessagesForLocalDiagnostics() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LombokCustomLogSmokeTest.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            log.trace("trace message");
            log.debug("debug message");
            log.info("info message");
            log.info("info message {}", "with argument");
            log.warn("warn message");
            log.warn("warn message {}", "with argument");
            log.error("error message");
            log.error("error message {}", "with argument");
        } finally {
            logger.setLevel(originalLevel);
        }
    }
}