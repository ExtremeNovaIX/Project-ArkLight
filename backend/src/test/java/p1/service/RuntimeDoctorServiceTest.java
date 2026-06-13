package p1.service;

import org.junit.jupiter.api.Test;
import p1.component.agent.stt.SttConfig;
import p1.component.agent.tts.TtsConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeDoctorServiceTest {

    @Test
    void shouldReportHardErrorsWhenSherpaQwenRuntimeOrModelsAreMissing() throws Exception {
        Path workspace = tempWorkspace();
        Files.createDirectories(workspace.resolve("config"));
        Files.createDirectories(workspace.resolve(Path.of("docs", "contracts")));
        Files.writeString(workspace.resolve(Path.of("docs", "contracts", "arclight-api.openapi.json")), "{}");

        RuntimeDoctorService service = new RuntimeDoctorService(
                new TestSttConfig(workspace),
                disabledTtsConfig(),
                (host, port, timeoutMs) -> false,
                () -> workspace,
                () -> workspace.resolve("config"),
                fixedClock(),
                (engine, python) -> false);

        RuntimeDoctorService.DoctorSnapshot snapshot = service.snapshot();
        Map<String, RuntimeDoctorService.DoctorCheck> checks = checksById(snapshot);

        assertEquals("ERROR", snapshot.status());
        assertEquals("ERROR", checks.get("stt.sherpa-qwen-sidecar-entry").status());
        assertEquals("ERROR", checks.get("stt.sherpa-qwen-model").status());
        assertEquals("ERROR", checks.get("stt.sherpa-diarization-model").status());
        assertEquals("ERROR", checks.get("stt.sherpa-qwen-runtime").status());
    }

    @Test
    void shouldPassRequiredSherpaQwenChecksWhenRuntimeFilesProbesAndPortAreAvailable() throws Exception {
        Path workspace = tempWorkspace();
        Files.createDirectories(workspace.resolve("config"));
        Files.createDirectories(workspace.resolve(Path.of("backend", "runtime", "python", ".venv", "Scripts")));
        Files.writeString(workspace.resolve(Path.of("backend", "runtime", "python", ".venv", "Scripts", "python.exe")), "");
        Files.createDirectories(workspace.resolve(Path.of("backend", "runtime", "asr", "sherpa-qwen", ".venv", "Scripts")));
        Files.writeString(workspace.resolve(Path.of("backend", "runtime", "asr", "sherpa-qwen", ".venv", "Scripts", "python.exe")), "");
        Files.createDirectories(workspace.resolve(Path.of("backend", "tools", "asr")));
        Files.writeString(workspace.resolve(Path.of("backend", "tools", "asr", "sherpa_qwen_sidecar.py")), "");
        createSherpaQwenModel(workspace);
        createDiarizationModel(workspace);
        Files.createDirectories(workspace.resolve(Path.of("docs", "contracts")));
        Files.writeString(workspace.resolve(Path.of("docs", "contracts", "arclight-api.openapi.json")), "{}");

        RuntimeDoctorService service = new RuntimeDoctorService(
                new TestSttConfig(workspace),
                disabledTtsConfig(),
                (host, port, timeoutMs) -> true,
                () -> workspace,
                () -> workspace.resolve("config"),
                fixedClock(),
                (engine, python) -> true);

        RuntimeDoctorService.DoctorSnapshot snapshot = service.snapshot();
        Map<String, RuntimeDoctorService.DoctorCheck> checks = checksById(snapshot);

        assertEquals("OK", checks.get("runtime.config-dir").status());
        assertEquals("OK", checks.get("runtime.python").status());
        assertEquals("OK", checks.get("stt.engine").status());
        assertEquals("OK", checks.get("stt.sherpa-qwen-sidecar-entry").status());
        assertEquals("OK", checks.get("stt.sidecar-port").status());
        assertEquals("OK", checks.get("stt.sherpa-qwen-model").status());
        assertEquals("OK", checks.get("stt.sherpa-diarization-model").status());
        assertEquals("OK", checks.get("stt.sherpa-qwen-runtime").status());
        assertEquals("OK", checks.get("contract.openapi").status());
    }

    private static void createSherpaQwenModel(Path workspace) throws Exception {
        Path modelDir = workspace.resolve(Path.of(
                "backend", "runtime", "asr", "1.7B"));
        Files.createDirectories(modelDir.resolve("tokenizer"));
        Files.writeString(modelDir.resolve("conv_frontend.onnx"), "");
        Files.writeString(modelDir.resolve("encoder.int8.onnx"), "");
        Files.writeString(modelDir.resolve("decoder.int8.onnx"), "");
        Files.writeString(modelDir.resolve(Path.of("tokenizer", "vocab.json")), "");
        Files.writeString(modelDir.resolve(Path.of("tokenizer", "merges.txt")), "");
        Files.writeString(modelDir.resolve(Path.of("tokenizer", "tokenizer_config.json")), "");
    }

    private static void createDiarizationModel(Path workspace) throws Exception {
        Path modelDir = workspace.resolve(Path.of("backend", "runtime", "asr", "sherpa-qwen", "models", "diarization"));
        Files.createDirectories(modelDir.resolve("sherpa-onnx-pyannote-segmentation-3-0"));
        Files.writeString(modelDir.resolve(Path.of("sherpa-onnx-pyannote-segmentation-3-0", "model.int8.onnx")), "");
        Files.writeString(modelDir.resolve("3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"), "");
    }

    private static Map<String, RuntimeDoctorService.DoctorCheck> checksById(RuntimeDoctorService.DoctorSnapshot snapshot) {
        return snapshot.checks().stream()
                .collect(Collectors.toMap(RuntimeDoctorService.DoctorCheck::id, check -> check));
    }

    private static TtsConfig disabledTtsConfig() {
        TtsConfig config = new TtsConfig();
        config.setEnabled(false);
        return config;
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneOffset.UTC);
    }

    private static Path tempWorkspace() throws Exception {
        Path workspace = Path.of("target", "runtime-doctor-test", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(workspace);
        return workspace;
    }

    private static final class TestSttConfig extends SttConfig {
        private final Path workspace;

        private TestSttConfig(Path workspace) {
            this.workspace = workspace;
        }

        @Override
        public int serverPort() {
            return 6006;
        }

        @Override
        public String projectRuntimePythonPath() {
            return workspace.resolve(Path.of("backend", "runtime", "python", ".venv", "Scripts", "python.exe")).toString();
        }

        @Override
        public String pythonBootstrapHint() {
            return "powershell -ExecutionPolicy Bypass -File backend/tools/python/bootstrap.ps1";
        }

        @Override
        public String sherpaQwenPythonExecutable() {
            return workspace.resolve(Path.of("backend", "runtime", "asr", "sherpa-qwen", ".venv", "Scripts", "python.exe")).toString();
        }

        @Override
        public String sherpaQwenSidecarScriptPath() {
            return workspace.resolve(Path.of("backend", "tools", "asr", "sherpa_qwen_sidecar.py")).toString();
        }

        @Override
        public String sherpaQwenModelDir() {
            return workspace.resolve(Path.of(
                    "backend", "runtime", "asr", "1.7B")).toString();
        }

        @Override
        public String sherpaDiarizationModelDir() {
            return workspace.resolve(Path.of("backend", "runtime", "asr", "sherpa-qwen", "models", "diarization")).toString();
        }

        @Override
        public String sherpaQwenLogFilePath() {
            return workspace.resolve(Path.of("backend", "runtime", "asr", "sherpa-qwen", "sherpa-qwen-sidecar.log")).toString();
        }

        @Override
        public String sherpaQwenBootstrapHint() {
            return "powershell -ExecutionPolicy Bypass -File backend/tools/asr/bootstrap-sherpa-qwen.ps1";
        }
    }
}
