package p1.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LoggedOperation;
import p1.model.dto.StoryReplayRequestDTO;
import p1.service.test.StoryReplayService;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/test/story-replay")
@RequiredArgsConstructor
public class StoryReplayController {

    private final StoryReplayService storyReplayService;

    @LoggedOperation(domain = LogDomain.HTTP, operation = "story.replay.start")

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) StoryReplayRequestDTO request) {
        try {
            return ResponseEntity.ok(storyReplayService.replayStoryFromFile(request));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }
}
