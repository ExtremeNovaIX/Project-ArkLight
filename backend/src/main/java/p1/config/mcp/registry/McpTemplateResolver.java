package p1.config.mcp.registry;

import p1.config.mcp.MCPProperties;

public final class McpTemplateResolver {

    public MCPProperties.GameMCPConfig resolveTemplate(MCPProperties.GameMCPConfig template, String installPath) {
        MCPProperties.GameMCPConfig resolved = template.copy();
        if (resolved.getArgs() != null) {
            String[] resolvedArgs = new String[resolved.getArgs().length];
            for (int i = 0; i < resolved.getArgs().length; i++) {
                resolvedArgs[i] = resolved.getArgs()[i].replace("{{installPath}}", installPath);
            }
            resolved.setArgs(resolvedArgs);
        }
        if (resolved.getUrl() != null) {
            resolved.setUrl(resolved.getUrl().replace("{{installPath}}", installPath));
        }
        if (resolved.getCommand() != null) {
            resolved.setCommand(resolved.getCommand().replace("{{installPath}}", installPath));
        }
        return resolved;
    }
}
