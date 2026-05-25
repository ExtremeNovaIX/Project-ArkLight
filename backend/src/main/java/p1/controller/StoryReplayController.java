package p1.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import p1.model.dto.StoryReplayRequestDTO;
import p1.service.test.StoryReplayService;

import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/test/story-replay")
@RequiredArgsConstructor
@Slf4j
public class StoryReplayController {

    private final StoryReplayService storyReplayService;

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody(required = false) StoryReplayRequestDTO request) {
        try {
            return ResponseEntity.ok(storyReplayService.replayStoryFromFile(request));
        } catch (Exception e) {
            log.warn("Story replay failed, reason={}", e.toString());
            return ResponseEntity.internalServerError().body(Map.of("message", e.getMessage()));
        }
    }
}
