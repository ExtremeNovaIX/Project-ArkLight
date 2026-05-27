package p1.component.agent.tts;

import org.junit.jupiter.api.Test;

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
}
