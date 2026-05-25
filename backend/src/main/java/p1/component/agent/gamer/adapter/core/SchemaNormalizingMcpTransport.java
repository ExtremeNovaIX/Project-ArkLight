package p1.component.agent.gamer.adapter.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.mcp.client.protocol.McpClientMessage;
import dev.langchain4j.mcp.client.protocol.McpInitializeRequest;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * McpTransport wrapper that normalizes JSON Schema {@code anyOf} constructs
 * in MCP tool definitions before they reach LangChain4j's
 * {@code ToolSpecificationHelper}.
 * <p>
 * LangChain4j MCP 1.0.0-beta5 fails to parse {@code {"type": "null"}} inside
 * {@code anyOf} arrays, throwing "Unknown element type: null". Python MCP
 * servers (FastMCP) generate {@code anyOf} for {@code Optional[str]} /
 * {@code str | None} parameters.
 * <p>
 * This wrapper collapses simple "X or null" anyOf patterns into a plain
 * {@code type} declaration, e.g.:
 * <pre>{@code
 *   {"anyOf": [{"type": "string"}, {"type": "null"}], "default": null}
 *     →
 *   {"type": "string", "default": null}
 * }</pre>
 */
@Slf4j
public class SchemaNormalizingMcpTransport implements McpTransport {

    private final McpTransport delegate;

    public SchemaNormalizingMcpTransport(McpTransport delegate) {
        this.delegate = delegate;
    }

    @Override
    public void start(McpOperationHandler handler) {
        delegate.start(handler);
    }

    @Override
    public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) {
        return delegate.initialize(request);
    }

    @Override
    public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage message) {
        return delegate.executeOperationWithResponse(message)
                .thenApply(SchemaNormalizingMcpTransport::normalizeResponse);
    }

    @Override
    public void executeOperationWithoutResponse(McpClientMessage message) {
        delegate.executeOperationWithoutResponse(message);
    }

    @Override
    public void checkHealth() {
        delegate.checkHealth();
    }

    @Override
    public void onFailure(Runnable callback) {
        delegate.onFailure(callback);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    static JsonNode normalizeResponse(JsonNode response) {
        JsonNode tools = response.at("/result/tools");
        if (!tools.isArray()) {
            return response;
        }
        boolean changed = false;
        for (JsonNode tool : tools) {
            JsonNode schema = tool.get("inputSchema");
            if (schema != null && schema.isObject()) {
                changed |= normalizeSchema((ObjectNode) schema);
            }
        }
        if (changed) {
            log.debug("[MCP schema] normalized anyOf schemas in tools/list response");
        }
        return response;
    }

    /**
     * Recursively walks a JSON Schema object and collapses simple
     * "X or null" anyOf patterns.
     *
     * @return true if any change was made
     */
    private static boolean normalizeSchema(ObjectNode node) {
        boolean changed = false;

        // Walk nested properties
        JsonNode properties = node.get("properties");
        if (properties != null && properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> iter = properties.fields();
            while (iter.hasNext()) {
                Map.Entry<String, JsonNode> prop = iter.next();
                JsonNode value = prop.getValue();
                if (value.isObject()) {
                    ObjectNode propNode = (ObjectNode) value;
                    if (collapseAnyOf(propNode)) {
                        changed = true;
                    }
                    changed |= normalizeSchema(propNode);
                }
            }
        }

        // Walk array items
        JsonNode items = node.get("items");
        if (items != null && items.isObject()) {
            ObjectNode itemsNode = (ObjectNode) items;
            if (collapseAnyOf(itemsNode)) {
                changed = true;
            }
            changed |= normalizeSchema(itemsNode);
        }

        // Handle top-level anyOf on the schema itself (unlikely but safe)
        if (collapseAnyOf(node)) {
            changed = true;
        }

        return changed;
    }

    /**
     * If {@code node} has an {@code anyOf} that is a simple "X or null"
     * pattern, replace it with a plain {@code type} declaration.
     *
     * @return true if the node was modified
     */
    static boolean collapseAnyOf(ObjectNode node) {
        JsonNode anyOf = node.get("anyOf");
        if (anyOf == null || !anyOf.isArray() || anyOf.size() != 2) {
            return false;
        }

        JsonNode nonNull = findNonNullElement(anyOf);
        if (nonNull == null) {
            return false;
        }

        // Remove anyOf, set type and merge extra fields from the non-null element
        node.remove("anyOf");
        copyMissing(node, nonNull);
        return true;
    }

    /**
     * Given a 2-element anyOf array, returns the element whose type is NOT "null".
     */
    private static JsonNode findNonNullElement(JsonNode anyOf) {
        JsonNode first = anyOf.get(0);
        JsonNode second = anyOf.get(1);
        String firstType = first.path("type").asText("");
        String secondType = second.path("type").asText("");
        if ("null".equals(firstType) && !"null".equals(secondType)) {
            return second;
        }
        if ("null".equals(secondType) && !"null".equals(firstType)) {
            return first;
        }
        return null; // more complex anyOf, leave it alone
    }

    /**
     * Copies fields from {@code source} to {@code target} for keys
     * that don't already exist in {@code target}.
     */
    private static void copyMissing(ObjectNode target, JsonNode source) {
        if (!source.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if ("type".equals(key)) {
                target.set(key, field.getValue());
            } else if (!target.has(key)) {
                target.set(key, field.getValue());
            }
        }
    }
}
