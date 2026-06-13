package p1.component.agent.stt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SttServerManagerTest {

    Path tempDir;

    @BeforeEach
    void createWorkspaceTempDirectory() throws Exception {
        tempDir = Path.of("target", "stt-server-manager-test", UUID.randomUUID().toString()).toAbsolutePath().normalize();
        Files.createDirectories(tempDir);
    }

    @Test
    void shouldRefuseManagedStartWhenSidecarPortIsAlreadyListening() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        SttServerManager manager = new SttServerManager(
                readyConfig(),
                (host, port, timeoutMs) -> true,
                builder -> {
                    starts.incrementAndGet();
                    return stoppedProcess();
                });

        manager.startIfConfigured();

        assertEquals(0, starts.get());
        assertFalse(manager.isRunning());
    }

    @Test
    void shouldStartSherpaQwenSidecar() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        AtomicReference<ProcessBuilder> captured = new AtomicReference<>();
        SttServerManager manager = new SttServerManager(
                readyConfig(),
                (host, port, timeoutMs) -> probes.incrementAndGet() >= 3,
                builder -> {
                    captured.set(builder);
                    return runningProcess();
                });

        manager.startIfConfigured();

        assertTrue(manager.isRunning());
        assertTrue(captured.get().command().contains(tempDir.resolve("sherpa_qwen_sidecar.py").toString()));
        assertTrue(captured.get().command().contains("--qwen-model-dir"));
        assertTrue(captured.get().command().contains("--diarization-model-dir"));
    }
    @Test
    void shouldRejectLegacyEngineRequestWithoutStartingSidecar() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger starts = new AtomicInteger();
        SttServerManager manager = new SttServerManager(
                readyConfig(),
                (host, port, timeoutMs) -> {
                    probes.incrementAndGet();
                    return true;
                },
                builder -> {
                    starts.incrementAndGet();
                    return runningProcess();
                });

        manager.startIfConfigured("funasr-2pass-win");

        assertFalse(manager.isRunning("funasr-2pass-win"));
        assertEquals(0, probes.get());
        assertEquals(0, starts.get());
        assertTrue(manager.clientUnavailableMessage("FUNASR_2PASS_WIN").contains("Unsupported STT engine"));
    }
    @Test
    void shouldStopRestartingAfterMaxFailedStarts() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        SttServerManager manager = new SttServerManager(
                readyConfig(),
                (host, port, timeoutMs) -> false,
                builder -> {
                    starts.incrementAndGet();
                    return stoppedProcess();
                });

        manager.startIfConfigured();
        manager.healthCheck();
        manager.healthCheck();
        manager.healthCheck();
        manager.healthCheck();

        assertEquals(3, starts.get());
        assertFalse(manager.isRunning());
    }

    @Test
    void shouldNotRestartDeadManagedProcessWhenPortBecomesOccupied() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger probes = new AtomicInteger();
        SttServerManager manager = new SttServerManager(
                readyConfig(),
                (host, port, timeoutMs) -> probes.incrementAndGet() >= 3,
                builder -> {
                    starts.incrementAndGet();
                    return stoppedProcess();
                });

        manager.startIfConfigured();
        manager.healthCheck();

        assertEquals(1, starts.get());
        assertFalse(manager.isRunning());
    }

    @Test
    void shouldNotStartWhenSherpaRuntimeIsMissing() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        SttServerManager manager = new SttServerManager(
                new TestSttConfig(tempDir.resolve("missing-python.exe"), tempDir.resolve("sherpa_qwen_sidecar.py")),
                (host, port, timeoutMs) -> false,
                builder -> {
                    starts.incrementAndGet();
                    return runningProcess();
                });

        manager.startIfConfigured();

        assertEquals(0, starts.get());
        assertFalse(manager.isRunning());
    }

    @Test
    void shouldNotStartWhenSherpaRuntimeProbeFails() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        SttServerManager manager = new SttServerManager(
                readyConfig(),
                (host, port, timeoutMs) -> false,
                builder -> {
                    starts.incrementAndGet();
                    return runningProcess();
                },
                (engine, python) -> false);

        manager.startIfConfigured();

        assertEquals(0, starts.get());
        assertFalse(manager.isRunning());
    }

    private TestSttConfig readyConfig() throws Exception {
        Path python = tempDir.resolve("python.exe");
        Path script = tempDir.resolve("sherpa_qwen_sidecar.py");
        Files.writeString(python, "");
        Files.writeString(script, "print('sherpa-qwen')");
        return new TestSttConfig(python, script);
    }

    private Process stoppedProcess() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(1);
        return process;
    }

    private Process runningProcess() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        return process;
    }

    private final class TestSttConfig extends SttConfig {
        private final Path python;
        private final Path script;

        private TestSttConfig(Path python, Path script) {
            this.python = python;
            this.script = script;
        }

        @Override
        public String sherpaQwenPythonExecutable() {
            return python.toString();
        }

        @Override
        public String sherpaQwenSidecarScriptPath() {
            return script.toString();
        }

        @Override
        public boolean sherpaQwenModelAvailable() {
            return true;
        }

        @Override
        public boolean sherpaDiarizationModelAvailable() {
            return true;
        }

        @Override
        public String sherpaQwenModelDir() {
            return python.getParent().resolve("qwen-model").toString();
        }

        @Override
        public String sherpaDiarizationModelDir() {
            return python.getParent().resolve("diarization").toString();
        }

        @Override
        public String sherpaQwenLogFilePath() {
            return python.getParent().resolve("sherpa-qwen.log").toString();
        }

        @Override
        public List<String> sherpaQwenRuntimeCommand() {
            return List.of(
                    python.toString(),
                    script.toString(),
                    "--qwen-model-dir", sherpaQwenModelDir(),
                    "--diarization-model-dir", sherpaDiarizationModelDir());
        }

        @Override
        public long runtimeStartupWaitMs() {
            return 0L;
        }
    }
}
