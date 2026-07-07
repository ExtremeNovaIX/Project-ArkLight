package p1.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApiContractDocumentTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, Set<String>> REQUIRED_OPERATIONS = Map.ofEntries(
            Map.entry("/api/doctor/status", Set.of("get")),
            Map.entry("/api/chat/send", Set.of("post")),
            Map.entry("/api/chat/live", Set.of("get")),
            Map.entry("/api/tts/live", Set.of("get")),
            Map.entry("/api/gamer/loop/start", Set.of("post")),
            Map.entry("/api/gamer/loop/stop", Set.of("post")),
            Map.entry("/api/gamer/loop/pause", Set.of("post")),
            Map.entry("/api/gamer/loop/resume", Set.of("post")),
            Map.entry("/api/gamer/loop/status", Set.of("get")),
            Map.entry("/api/test/story-replay/start", Set.of("post")),
            Map.entry("/stt/stream", Set.of("get"))
    );
    private static final Set<String> REMOVED_PATHS = Set.of(
            "/api/chat/typing"
    );

    @Test
    void shouldKeepMachineReadableContractForFrontendEndpoints() throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(contractPath().toFile());

        assertEquals("3.1.0", root.path("openapi").asText());
        JsonNode paths = root.path("paths");
        REQUIRED_OPERATIONS.forEach((path, methods) -> {
            assertTrue(paths.has(path), "Missing path in OpenAPI contract: " + path);
            methods.forEach(method ->
                    assertTrue(paths.path(path).has(method), "Missing method in OpenAPI contract: " + method + " " + path));
        });
        REMOVED_PATHS.forEach(path ->
                assertFalse(paths.has(path), "Removed path still exists in OpenAPI contract: " + path));

        JsonNode doctorSchema = root.at("/components/schemas/DoctorSnapshot/properties");
        assertTrue(doctorSchema.has("status"));
        assertTrue(doctorSchema.has("hasIssues"));
        assertTrue(doctorSchema.has("checks"));
    }

    @Test
    void shouldNotContainBrokenLocalReferencesOrDuplicateOperationIds() throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(contractPath().toFile());
        Set<String> operationIds = new HashSet<>();

        validateReferences(root, root);
        root.path("paths").fields().forEachRemaining(pathEntry ->
                pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
                    JsonNode operationId = methodEntry.getValue().path("operationId");
                    if (!operationId.isMissingNode() && !operationId.asText().isBlank()) {
                        assertTrue(operationIds.add(operationId.asText()),
                                "Duplicate operationId in OpenAPI contract: " + operationId.asText());
                    }
                }));
    }

    private void validateReferences(JsonNode root, JsonNode current) {
        if (current.isObject()) {
            JsonNode reference = current.get("$ref");
            if (reference != null && reference.isTextual() && reference.asText().startsWith("#/")) {
                assertFalse(root.at(reference.asText().substring(1)).isMissingNode(),
                        "Broken OpenAPI local reference: " + reference.asText());
            }
            current.fields().forEachRemaining(entry -> validateReferences(root, entry.getValue()));
        } else if (current.isArray()) {
            current.forEach(item -> validateReferences(root, item));
        }
    }

    private Path contractPath() {
        Path fromBackend = Path.of("..", "docs", "contracts", "arclight-api.openapi.json").toAbsolutePath().normalize();
        if (Files.isRegularFile(fromBackend)) {
            return fromBackend;
        }
        return Path.of("docs", "contracts", "arclight-api.openapi.json").toAbsolutePath().normalize();
    }
}
