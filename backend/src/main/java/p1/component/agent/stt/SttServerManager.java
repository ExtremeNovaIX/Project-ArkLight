package p1.component.agent.stt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 管理本地 ASR sidecar 生命周期。
 */
@Component
@Slf4j
public class SttServerManager {

    private static final int MAX_START_ATTEMPTS = 3;
    private static final String LOCALHOST = "127.0.0.1";
    private static final int PORT_PROBE_TIMEOUT_MS = 300;
    private static final int STARTUP_READY_POLL_INTERVAL_MS = 100;
    private static final long SHERPA_QWEN_STARTUP_READY_TIMEOUT_MS = 5_000L;

    private final SttConfig config;
    private final PortProbe portProbe;
    private final ProcessStarter processStarter;
    private final RuntimeProbe runtimeProbe;
    private final Map<String, SidecarState> states = new ConcurrentHashMap<>();

    @Autowired
    public SttServerManager(SttConfig config) {
        this(config, SttServerManager::isTcpPortOpen, ProcessBuilder::start, SttServerManager::asrRuntimeCanStart);
    }

    SttServerManager(SttConfig config, PortProbe portProbe, ProcessStarter processStarter) {
        this(config, portProbe, processStarter, (engine, python) -> true);
    }

    SttServerManager(SttConfig config,
                     PortProbe portProbe,
                     ProcessStarter processStarter,
                     RuntimeProbe runtimeProbe) {
        this.config = config;
        this.portProbe = portProbe;
        this.processStarter = processStarter;
        this.runtimeProbe = runtimeProbe;
    }

    @PostConstruct
    public void startIfConfigured() {
        if (!config.runtimeAutoStartEnabled()) {
            return;
        }
        ensureRunning(config.sttEngine());
    }

    public synchronized void startIfConfigured(String engine) {
        if (!config.runtimeAutoStartEnabled()) {
            return;
        }
        ensureRunning(engine);
    }

    @PreDestroy
    public void stopIfStarted() {
        states.values().forEach(this::stopProcess);
    }

    @Scheduled(fixedDelay = 30_000)
    public synchronized void healthCheck() {
        states.forEach((engine, state) -> healthCheck(engine, state));
    }

    public boolean isRunning() {
        return isRunning(config.sttEngine());
    }

    public synchronized boolean isRunning(String engine) {
        SidecarState state = states.get(config.normalizeEngine(engine));
        return sidecarReady(state);
    }

    public String clientUnavailableMessage() {
        return clientUnavailableMessage(config.sttEngine());
    }

    public synchronized String clientUnavailableMessage(String engine) {
        String normalized = config.normalizeEngine(engine);
        ensureRunning(normalized);
        SidecarState state = states.get(normalized);
        if (sidecarReady(state)) {
            return "";
        }
        if (state.unavailableMessage != null && !state.unavailableMessage.isBlank()) {
            return state.unavailableMessage;
        }
        return engineLabel(normalized) + " ASR sidecar is not running. Check " + logFilePath(normalized) + ".";
    }

    private boolean ensureRunning(String requestedEngine) {
        String engine = config.normalizeEngine(requestedEngine);
        if (!config.supportedEngine(engine)) {
            SidecarState state = states.computeIfAbsent(engine, key -> new SidecarState(unsupportedEngineMessage(key)));
            state.ready = false;
            state.exhausted = true;
            state.process = null;
            state.unavailableMessage = unsupportedEngineMessage(engine);
            log.error("[STT] Unsupported ASR engine requested: {}", engine);
            return false;
        }
        SidecarState state = states.computeIfAbsent(engine, key -> new SidecarState(defaultUnavailableMessage(key)));
        if (state.process != null && state.process.isAlive()) {
            if (state.ready) {
                state.unavailableMessage = "";
                return true;
            }
            return waitUntilSidecarAcceptsConnections(engine, state, Path.of(logFilePath(engine)).toAbsolutePath().normalize());
        }
        state.ready = false;
        if (state.exhausted) {
            return false;
        }
        if (sidecarPortOccupied(engine)) {
            state.unavailableMessage = engineLabel(engine) + " ASR sidecar port " + LOCALHOST + ":" + config.enginePort(engine)
                    + " is occupied by another process. Stop that process before starting voice capture.";
            log.error("[STT] {} sidecar port is already occupied: {}:{}.", engineLabel(engine), LOCALHOST, config.enginePort(engine));
            state.exhausted = true;
            state.process = null;
            return false;
        }
        if (!runtimeAvailable(engine)) {
            state.exhausted = true;
            state.unavailableMessage = engineLabel(engine) + " runtime or model files are not ready. " + bootstrapHint(engine)
                    + " Check " + logFilePath(engine) + ".";
            log.error("[STT] {} runtime is not ready. python={}, script={}, hint={}",
                    engineLabel(engine), pythonExecutable(engine), sidecarScriptPath(engine), bootstrapHint(engine));
            return false;
        }
        return startProcess(engine, state);
    }

    private void healthCheck(String engine, SidecarState state) {
        if (state.process == null || state.exhausted) {
            return;
        }
        if (state.process.isAlive()) {
            return;
        }

        int exitCode = state.process.exitValue();
        log.warn("[STT] {} sidecar exited: exitCode={}, attempts={}/{}",
                engineLabel(engine), exitCode, state.startAttempts, MAX_START_ATTEMPTS);
        state.process = null;
        state.ready = false;

        if (sidecarPortOccupied(engine)) {
            state.exhausted = true;
            state.unavailableMessage = engineLabel(engine) + " ASR sidecar exited, but port "
                    + LOCALHOST + ":" + config.enginePort(engine) + " is now occupied by another process.";
            log.error("[STT] {} sidecar exited but port {}:{} is occupied by another process.",
                    engineLabel(engine), LOCALHOST, config.enginePort(engine));
            return;
        }
        if (state.startAttempts < MAX_START_ATTEMPTS) {
            startProcess(engine, state);
            return;
        }

        state.exhausted = true;
        state.unavailableMessage = engineLabel(engine) + " ASR sidecar reached max start attempts. Check "
                + logFilePath(engine) + ".";
        log.error("[STT] {} sidecar reached max start attempts: max={}, logFile={}, hint={}",
                engineLabel(engine), MAX_START_ATTEMPTS, logFilePath(engine), bootstrapHint(engine));
    }

    private boolean startProcess(String engine, SidecarState state) {
        if (sidecarPortOccupied(engine)) {
            state.exhausted = true;
            state.unavailableMessage = engineLabel(engine) + " ASR sidecar port " + LOCALHOST + ":"
                    + config.enginePort(engine) + " is occupied by another process.";
            log.error("[STT] Refuse to start {} sidecar because port {}:{} is occupied.",
                    engineLabel(engine), LOCALHOST, config.enginePort(engine));
            return false;
        }
        if (state.startAttempts >= MAX_START_ATTEMPTS) {
            state.exhausted = true;
            return false;
        }

        try {
            state.unavailableMessage = engineLabel(engine) + " ASR sidecar is starting.";
            ProcessBuilder builder = new ProcessBuilder(runtimeCommand(engine));
            builder.redirectErrorStream(true);
            Path logFile = Path.of(logFilePath(engine)).toAbsolutePath().normalize();
            Path logParent = logFile.getParent();
            if (logParent != null) {
                Files.createDirectories(logParent);
            }
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            state.process = processStarter.start(builder);
            state.startAttempts++;
            log.info("[STT] {} sidecar started: port={}, attempt={}/{}, python={}, logFile={}",
                    engineLabel(engine), config.enginePort(engine), state.startAttempts, MAX_START_ATTEMPTS,
                    pythonExecutable(engine), logFile);

            state.ready = false;
            return waitUntilSidecarAcceptsConnections(engine, state, logFile);
        } catch (IOException e) {
            state.ready = false;
            state.unavailableMessage = "Failed to start " + engineLabel(engine) + " ASR sidecar: " + e.getMessage();
            log.error("[STT] Failed to start {} sidecar: {}", engineLabel(engine), e.getMessage());
        }
        return false;
    }

    private boolean sidecarReady(SidecarState state) {
        return state != null && state.process != null && state.process.isAlive() && state.ready;
    }

    private boolean waitUntilSidecarAcceptsConnections(String engine, SidecarState state, Path logFile) {
        long timeoutMs = startupReadyTimeoutMs(engine);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (state.process != null && state.process.isAlive()) {
            if (sidecarPortReady(engine)) {
                state.ready = true;
                state.unavailableMessage = "";
                log.info("[STT] {} sidecar is listening: port={}", engineLabel(engine), config.enginePort(engine));
                return true;
            }
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
            try {
                sleepBeforeNextReadyProbe(deadlineNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.ready = false;
                state.unavailableMessage = "Interrupted while waiting for " + engineLabel(engine) + " ASR sidecar startup.";
                log.warn("[STT] Interrupted while waiting for {} sidecar startup", engineLabel(engine));
                return false;
            }
        }

        state.ready = false;
        if (state.process != null && !state.process.isAlive()) {
            state.unavailableMessage = engineLabel(engine) + " ASR sidecar exited during startup. Check " + logFile + ".";
            log.warn("[STT] {} sidecar exited during startup: exitCode={}, logFile={}",
                    engineLabel(engine), state.process.exitValue(), logFile);
            return false;
        }

        state.unavailableMessage = engineLabel(engine) + " ASR sidecar is still starting; port "
                + LOCALHOST + ":" + config.enginePort(engine) + " was not ready within " + timeoutMs
                + "ms. Check " + logFile + ".";
        log.warn("[STT] {} sidecar did not listen on {}:{} within {}ms. logFile={}",
                engineLabel(engine), LOCALHOST, config.enginePort(engine), timeoutMs, logFile);
        return false;
    }

    private void sleepBeforeNextReadyProbe(long deadlineNanos) throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return;
        }
        long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        Thread.sleep(Math.min(STARTUP_READY_POLL_INTERVAL_MS, remainingMs));
    }
    private long startupReadyTimeoutMs(String engine) {
        long configured = Math.max(0L, config.runtimeStartupWaitMs());
        return Math.max(configured, SHERPA_QWEN_STARTUP_READY_TIMEOUT_MS);
    }

    private boolean sidecarPortReady(String engine) {
        try {
            return portProbe.isOpen(LOCALHOST, config.enginePort(engine), PORT_PROBE_TIMEOUT_MS);
        } catch (Exception e) {
            log.debug("[STT] ASR sidecar readiness probe failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean runtimeAvailable(String engine) {
        Path python = Path.of(pythonExecutable(engine));
        return Files.isRegularFile(python)
                && Files.isRegularFile(Path.of(sidecarScriptPath(engine)))
                && modelAvailable(engine)
                && runtimeProbe.check(engine, python);
    }

    private void stopProcess(SidecarState state) {
        state.ready = false;
        if (state.process == null || !state.process.isAlive()) {
            return;
        }
        state.process.destroy();
        log.info("[STT] ASR sidecar stop requested");
    }

    private boolean sidecarPortOccupied(String engine) {
        try {
            return portProbe.isOpen(LOCALHOST, config.enginePort(engine), PORT_PROBE_TIMEOUT_MS);
        } catch (Exception e) {
            log.debug("[STT] ASR sidecar port probe failed: {}", e.getMessage());
            return false;
        }
    }
    private String engineLabel(String engine) {
        if (SttConfig.SHERPA_QWEN_ENGINE.equals(config.normalizeEngine(engine))) {
            return "sherpa-qwen";
        }
        return config.normalizeEngine(engine);
    }
    private String pythonExecutable(String engine) {
        return config.sherpaQwenPythonExecutable();
    }
    private String sidecarScriptPath(String engine) {
        return config.sherpaQwenSidecarScriptPath();
    }
    private String logFilePath(String engine) {
        return config.sherpaQwenLogFilePath();
    }
    private String bootstrapHint(String engine) {
        return config.sherpaQwenBootstrapHint();
    }
    private boolean modelAvailable(String engine) {
        return config.sherpaQwenModelAvailable() && config.sherpaDiarizationModelAvailable();
    }
    private List<String> runtimeCommand(String engine) {
        return config.sherpaQwenRuntimeCommand();
    }

    private String defaultUnavailableMessage(String engine) {
        if (!config.supportedEngine(engine)) {
            return unsupportedEngineMessage(engine);
        }
        return engineLabel(engine) + " ASR sidecar has not started.";
    }

    private String unsupportedEngineMessage(String engine) {
        return "Unsupported STT engine: " + config.normalizeEngine(engine) + ". Configure stt.engine=sherpa-qwen-onnx.";
    }

    private static boolean isTcpPortOpen(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    private static boolean asrRuntimeCanStart(String engine, Path python) {
        String code = importProbeCode("sherpa_onnx", "numpy", "websockets");
        try {
            Process probe = new ProcessBuilder(python.toString(), "-c", code)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = probe.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String importProbeCode(String... modules) {
        String quoted = String.join(",", List.of(modules).stream()
                .map(module -> "'" + module + "'")
                .toList());
        return """
                import importlib.util
                missing = [name for name in (%s) if importlib.util.find_spec(name) is None]
                raise SystemExit(1 if missing else 0)
                """.formatted(quoted);
    }

    private static final class SidecarState {
        private Process process;
        private int startAttempts;
        private boolean ready;
        private boolean exhausted;
        private String unavailableMessage;

        private SidecarState(String unavailableMessage) {
            this.unavailableMessage = unavailableMessage;
        }
    }

    @FunctionalInterface
    interface PortProbe {
        boolean isOpen(String host, int port, int timeoutMs);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    @FunctionalInterface
    interface RuntimeProbe {
        boolean check(String engine, Path python);
    }
}
