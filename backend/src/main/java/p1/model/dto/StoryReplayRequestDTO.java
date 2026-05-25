package p1.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StoryReplayRequestDTO {
    private String sessionId;
    private String characterName;
    private Integer targetLength;
}
