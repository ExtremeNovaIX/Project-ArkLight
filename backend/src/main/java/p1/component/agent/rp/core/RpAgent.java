package p1.component.agent.rp.core;

import dev.langchain4j.service.*;

public interface RpAgent {
    @SystemMessage("{{systemPrompt}}")
    TokenStream chat(@MemoryId String sessionId,
                     @UserMessage String userMessage,
                     @V("systemPrompt") String systemPrompt);

    @SystemMessage("{{systemPrompt}}")
    TokenStream chatWithName(@MemoryId String sessionId,
                             @UserName String userName,
                             @UserMessage String userMessage,
                             @V("systemPrompt") String systemPrompt);
}
