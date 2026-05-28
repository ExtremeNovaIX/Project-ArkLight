package p1.component.agent.tts;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsRuntimeManagerTest {

    @Test
    void shouldManageGptRuntimeWhenGptProviderIsActive() {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider("gpt-sovits-http");
        config.getRuntime().setAutoStartEnabled(true);

        TtsRuntimeManager manager = new TtsRuntimeManager(config);

        assertTrue(manager.shouldWaitForManagedRuntime());
    }

    @Test
    void shouldManageVoxRuntimeWhenVoxProviderIsActive() {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider("voxcpm2-http");
        config.getVoxCpm2().getRuntime().setAutoStartEnabled(true);

        TtsRuntimeManager manager = new TtsRuntimeManager(config);

        assertTrue(manager.shouldWaitForManagedRuntime());
    }

    @Test
    void shouldNotManageVoxRuntimeWhenVoxRuntimeIsDisabled() {
        TtsConfig config = new TtsConfig();
        config.setEnabled(true);
        config.setProvider("voxcpm2-http");
        config.getVoxCpm2().getRuntime().setAutoStartEnabled(false);

        TtsRuntimeManager manager = new TtsRuntimeManager(config);

        assertFalse(manager.shouldWaitForManagedRuntime());
    }

    @Test
    void shouldResolveRelativeExecutableAgainstWorkingDirectory() {
        TtsRuntimeManager manager = new TtsRuntimeManager(new TtsConfig());
        Path workingDirectory = Path.of("E:/Project/backend/tts/runtime/VoxCPM2");

        List<String> command = manager.resolveExecutable(
                List.of(".venv\\Scripts\\python.exe", "..\\..\\..\\tools\\voxcpm2_tts_server.py"),
                workingDirectory);

        assertEquals(
                workingDirectory.resolve(".venv/Scripts/python.exe").normalize().toString(),
                command.getFirst());
        assertEquals("..\\..\\..\\tools\\voxcpm2_tts_server.py", command.get(1));
    }

    @Test
    void shouldLeavePathLookupExecutableUnchanged() {
        TtsRuntimeManager manager = new TtsRuntimeManager(new TtsConfig());

        List<String> command = manager.resolveExecutable(
                List.of("python", "api_v2.py"),
                Path.of("E:/Project/backend/tts/runtime/VoxCPM2"));

        assertEquals(List.of("python", "api_v2.py"), command);
    }
}
