package p1.component.gamer;

import org.junit.jupiter.api.Test;
import p1.component.agent.gamer.GamerRequestResolver;
import p1.config.mcp.GamerProperties;
import p1.config.mcp.MCPProperties;

import static org.junit.jupiter.api.Assertions.*;

class GamerRequestResolverTest {

    @Test
    void shouldUseExplicitGameNameFirst() {
        GamerRequestResolver resolver = resolverWithGames("game-a");

        assertEquals("game-b", resolver.resolveGameName(" game-b "));
    }

    @Test
    void shouldUseConfiguredDefaultGameName() {
        MCPProperties mcpProperties = mcpPropertiesWithGames("game-a", "game-b");
        GamerProperties gamerProperties = new GamerProperties();
        gamerProperties.setDefaultGameName("game-b");

        GamerRequestResolver resolver = new GamerRequestResolver(gamerProperties, mcpProperties);

        assertEquals("game-b", resolver.resolveGameName(null));
    }

    @Test
    void shouldAutoSelectOnlyEnabledGame() {
        GamerRequestResolver resolver = resolverWithGames("game-a");

        assertEquals("game-a", resolver.resolveGameName(null));
    }

    @Test
    void shouldRequireGameNameWhenMultipleGamesAreEnabled() {
        GamerRequestResolver resolver = resolverWithGames("game-a", "game-b");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveGameName(null));
        assertTrue(error.getMessage().contains("多个已启用"));
    }

    private GamerRequestResolver resolverWithGames(String... gameNames) {
        return new GamerRequestResolver(new GamerProperties(), mcpPropertiesWithGames(gameNames));
    }

    private MCPProperties mcpPropertiesWithGames(String... gameNames) {
        MCPProperties properties = new MCPProperties();
        for (String gameName : gameNames) {
            MCPProperties.GameMCPConfig config = new MCPProperties.GameMCPConfig();
            config.setEnabled(true);
            properties.putGame(gameName, config);
        }
        return properties;
    }
}
