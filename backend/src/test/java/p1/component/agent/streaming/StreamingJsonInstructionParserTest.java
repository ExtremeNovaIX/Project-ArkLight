package p1.component.agent.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingJsonInstructionParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseJsonAcrossChunks() {
        StreamingJsonInstructionParser parser = new StreamingJsonInstructionParser(objectMapper, 1024);

        assertTrue(parser.accept("先观察 {\"type\":\"action\",").isEmpty());
        List<StreamingJsonInstruction> instructions = parser.accept("\"operations\":[]} 后续文本");

        assertEquals(1, instructions.size());
        assertEquals("action", instructions.getFirst().json().path("type").asText());
    }

    @Test
    void shouldIgnoreBracesInsideString() {
        StreamingJsonInstructionParser parser = new StreamingJsonInstructionParser(objectMapper, 1024);

        List<StreamingJsonInstruction> instructions = parser.accept("""
                {"type":"action","summary":"说明里有 { 大括号 }","operations":[]}
                """);

        assertEquals(1, instructions.size());
        assertEquals("说明里有 { 大括号 }", instructions.getFirst().json().path("summary").asText());
    }

    @Test
    void shouldParseMultipleObjectsInOneChunk() {
        StreamingJsonInstructionParser parser = new StreamingJsonInstructionParser(objectMapper, 1024);

        List<StreamingJsonInstruction> instructions = parser.accept("""
                {"type":"action","operations":[]}{"type":"action","operations":[]}
                """);

        assertEquals(2, instructions.size());
    }

    @Test
    void shouldDropOversizedCandidate() {
        StreamingJsonInstructionParser parser = new StreamingJsonInstructionParser(objectMapper, 128);

        List<StreamingJsonInstruction> instructions = parser.accept("""
                {"type":"action","summary":"这个候选会超过限制，因为模型可能在 JSON 中塞入非常长的自然语言说明，这里需要让解析器丢弃当前候选，继续寻找后续真正可执行的 JSON 对象。这个候选会超过限制，因为模型可能在 JSON 中塞入非常长的自然语言说明，这里需要让解析器丢弃当前候选，继续寻找后续真正可执行的 JSON 对象。","operations":[]} {"type":"action","operations":[]}
                """);

        assertEquals(1, instructions.size());
    }
}
