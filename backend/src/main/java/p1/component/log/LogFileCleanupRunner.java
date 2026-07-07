package p1.component.log;

import lombok.CustomLog;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Component
@CustomLog
public class LogFileCleanupRunner implements ApplicationRunner {

    private static final Path LOG_DIR = Paths.get("logs");
    private static final String LOG_PREFIX = "app-";
    private static final String LOG_SUFFIX = ".log";
    private static final int MAX_LOG_FILES = 15;

    @Override
    public void run(ApplicationArguments args) {
        if (!Files.isDirectory(LOG_DIR)) {
            return;
        }

        try (Stream<Path> stream = Files.list(LOG_DIR)) {
            List<Path> logFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isManagedLogFile)
                    .sorted(Comparator.comparing(this::lastModifiedTime).reversed())
                    .toList();

            if (logFiles.size() <= MAX_LOG_FILES) {
                return;
            }

            for (int i = MAX_LOG_FILES; i < logFiles.size(); i++) {
                Path logFile = logFiles.get(i);
                Files.deleteIfExists(logFile);
                log.info(LogDomain.RUNTIME, "log.file_deleted", LogOutcome.SUCCEEDED, "fileName", logFile.getFileName());
            }
        } catch (IOException e) {
            log.warn(LogDomain.RUNTIME, "log.cleanup_failed", LogOutcome.DEGRADED, e);
        }
    }

    private boolean isManagedLogFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.startsWith(LOG_PREFIX) && fileName.endsWith(LOG_SUFFIX);
    }

    private long lastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}
