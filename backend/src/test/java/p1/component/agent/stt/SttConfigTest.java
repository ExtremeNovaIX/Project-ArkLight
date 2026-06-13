package p1.component.agent.stt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SttConfigTest {

    Path tempDir;

    @BeforeEach
    void createWorkspaceTempDirectory() throws Exception {
        tempDir = Path.of("target", "stt-config-test", UUID.randomUUID().toString()).toAbsolutePath().normalize();
        Files.createDirectories(tempDir);
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty("stt.asr.bundle-dir");
        System.clearProperty("stt.engine");
        System.clearProperty("stt.sherpa-qwen.python.executable");
        System.clearProperty("stt.sherpa-qwen.model-dir");
        System.clearProperty("stt.sherpa-qwen.diarization-model-dir");
        System.clearProperty("stt.partial-interval-ms");
        System.clearProperty("stt.max-segment-ms");
        System.clearProperty("stt.sherpa-qwen.hotwords");
        System.clearProperty("stt.sherpa-qwen.hotwords-file");
        System.clearProperty("stt.sherpa-qwen.max-hotwords");
    }

    @Test
    void shouldDefaultToSherpaQwenEngine() {
        assertEquals("sherpa-qwen-onnx", new TestSttConfig(tempDir).sttEngine());
    }

    @Test
    void shouldResolveProjectPythonRuntimeForSharedTools() throws Exception {
        Path python = tempDir.resolve(Path.of("backend", "runtime", "python", ".venv", "Scripts", "python.exe"));
        Files.createDirectories(python.getParent());
        Files.writeString(python, "");

        assertEquals(python.toAbsolutePath().normalize().toString(), new TestSttConfig(tempDir).projectRuntimePythonPath());
    }

    @Test
    void shouldDefaultSherpaQwenPythonToDedicatedRuntime() {
        Path expected = tempDir.resolve(Path.of(
                "backend", "runtime", "asr", "sherpa-qwen", ".venv", "Scripts", "python.exe"));

        assertEquals(expected.toAbsolutePath().normalize().toString(), new TestSttConfig(tempDir).sherpaQwenPythonExecutable());
    }

    @Test
    void shouldPreferExplicitSherpaQwenPythonProperty() {
        System.setProperty("stt.sherpa-qwen.python.executable", "E:\\ArcLight\\sherpa-python.exe");

        assertEquals(
                Path.of("E:\\ArcLight\\sherpa-python.exe").toAbsolutePath().normalize().toString(),
                new TestSttConfig(tempDir).sherpaQwenPythonExecutable());
    }

    @Test
    void shouldPreferExplicitSherpaQwenPythonEnvironment() {
        SttConfig config = new TestSttConfig(tempDir, Map.of("STT_SHERPA_QWEN_PYTHON_EXE", "E:\\ASR\\python.exe"));

        assertEquals(
                Path.of("E:\\ASR\\python.exe").toAbsolutePath().normalize().toString(),
                config.sherpaQwenPythonExecutable());
    }

    @Test
    void shouldExposeDefaultExternalAsrBundleDirectory() {
        SttConfig config = new TestSttConfig(tempDir);

        assertEquals(
                tempDir.resolve(Path.of("backend", "runtime", "asr", "custom")).toString(),
                config.externalAsrBundlePath());
    }

    @Test
    void shouldResolveSherpaQwenModelDirectoriesAndTiming() {
        System.setProperty("stt.partial-interval-ms", "250");
        System.setProperty("stt.max-segment-ms", "2200");

        SttConfig config = new TestSttConfig(tempDir);

        assertEquals(250, config.partialIntervalMs());
        assertEquals(2200, config.maxSegmentMs());
        assertTrue(config.sherpaQwenModelDir().endsWith("1.7B"));
        assertTrue(config.sherpaDiarizationModelDir().endsWith("diarization"));
    }

    @Test
    void shouldRejectLegacyFunAsrNamesInsteadOfFallingBack() {
        SttConfig config = new TestSttConfig(tempDir);

        assertEquals("sherpa-qwen-onnx", config.normalizeEngine(""));
        assertEquals("funasr-2pass-win", config.normalizeEngine("FUNASR_2PASS_WIN"));
        assertEquals(6006, config.enginePort("sherpa-qwen-onnx"));
        assertThrows(IllegalArgumentException.class, () -> config.enginePort("funasr-2pass-win"));
        assertTrue(config.supportedEngine("sherpa-qwen-onnx"));
        assertFalse(config.supportedEngine("funasr-2pass-win"));
    }
    @Test
    void shouldRequireQwenOnnxModelFiles() throws Exception {
        Path modelDir = tempDir.resolve(Path.of(
                "backend", "runtime", "asr", "1.7B"));
        Files.createDirectories(modelDir.resolve("tokenizer"));
        Files.writeString(modelDir.resolve("conv_frontend.onnx"), "");
        Files.writeString(modelDir.resolve("encoder.int8.onnx"), "");
        Files.writeString(modelDir.resolve("decoder.int8.onnx"), "");
        Files.writeString(modelDir.resolve(Path.of("tokenizer", "vocab.json")), "");
        Files.writeString(modelDir.resolve(Path.of("tokenizer", "merges.txt")), "");

        SttConfig config = new TestSttConfig(tempDir);
        assertFalse(config.sherpaQwenModelAvailable());

        Files.writeString(modelDir.resolve(Path.of("tokenizer", "tokenizer_config.json")), "");
        assertTrue(config.sherpaQwenModelAvailable());
    }

    @Test
    void shouldRequireDiarizationModels() throws Exception {
        Path modelDir = tempDir.resolve(Path.of("backend", "runtime", "asr", "sherpa-qwen", "models", "diarization"));
        Files.createDirectories(modelDir.resolve("sherpa-onnx-pyannote-segmentation-3-0"));
        Files.writeString(modelDir.resolve(Path.of("sherpa-onnx-pyannote-segmentation-3-0", "model.int8.onnx")), "");

        SttConfig config = new TestSttConfig(tempDir);
        assertFalse(config.sherpaDiarizationModelAvailable());

        Files.writeString(modelDir.resolve("3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"), "");
        assertTrue(config.sherpaDiarizationModelAvailable());
    }

    @Test
    void sherpaQwenRuntimeCommandShouldUseAsrV2Sidecar() {
        SttConfig config = new TestSttConfig(tempDir);
        List<String> command = config.sherpaQwenRuntimeCommand();

        assertTrue(command.contains(config.sherpaQwenSidecarScriptPath()));
        assertTrue(command.contains("--partial-interval-ms"));
        assertTrue(command.contains("--max-segment-ms"));
        assertFalse(command.contains("qwen_vllm_sidecar.py"));
    }


    @Test
    void sherpaQwenRuntimeCommandShouldPassHotwords() throws Exception {
        Path hotwords = tempDir.resolve("hotwords.txt");
        Files.writeString(hotwords, "开团\n补枪\n");
        System.setProperty("stt.sherpa-qwen.hotwords", "peek,farm");
        System.setProperty("stt.sherpa-qwen.hotwords-file", hotwords.toString());

        List<String> command = new TestSttConfig(tempDir).sherpaQwenRuntimeCommand();

        assertTrue(command.contains("--hotwords"));
        assertTrue(command.contains("peek,farm"));
        assertTrue(command.contains("--hotwords-file"));
        assertTrue(command.contains(hotwords.toAbsolutePath().normalize().toString()));
        assertTrue(command.contains("--max-hotwords"));
        assertTrue(command.contains("300"));
    }

    @Test
    void shouldReadHotwordsFromSpringConfigWhenNoPropertyOrEnvironment() throws Exception {
        Path hotwords = tempDir.resolve("game-hotwords.txt");
        Files.writeString(hotwords, "开团\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("stt.sherpa-qwen.hotwords", "补枪")
                .withProperty("stt.sherpa-qwen.hotwords-file", hotwords.toString())
                .withProperty("stt.sherpa-qwen.max-hotwords", "128")
                .withProperty("stt.partial-interval-ms", "350")
                .withProperty("stt.max-segment-ms", "2400");

        SttConfig config = new TestSttConfig(tempDir, Map.of(), environment);

        assertEquals("补枪", config.sherpaQwenHotwords());
        assertEquals(hotwords.toAbsolutePath().normalize().toString(), config.sherpaQwenHotwordsFilePath());
        assertEquals(128, config.sherpaQwenMaxHotwords());
        assertEquals(350, config.partialIntervalMs());
        assertEquals(2400, config.maxSegmentMs());
    }


    private static final class TestSttConfig extends SttConfig {
        private final Path workingDirectory;
        private final Map<String, String> environment;

        private TestSttConfig(Path workingDirectory) {
            this(workingDirectory, Map.of());
        }

        private TestSttConfig(Path workingDirectory, Map<String, String> environment) {
            this(workingDirectory, environment, null);
        }

        private TestSttConfig(Path workingDirectory, Map<String, String> environment, MockEnvironment springEnvironment) {
            super(springEnvironment);
            this.workingDirectory = workingDirectory;
            this.environment = environment;
        }

        @Override
        Path workingDirectory() {
            return workingDirectory;
        }

        @Override
        String environment(String name) {
            return environment.get(name);
        }
    }
}
