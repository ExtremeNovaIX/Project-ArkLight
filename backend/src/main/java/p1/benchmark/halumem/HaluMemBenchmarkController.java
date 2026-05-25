package p1.benchmark.halumem;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Profile("benchmark")
@RequiredArgsConstructor
@RequestMapping("/api/benchmark/halumem")
public class HaluMemBenchmarkController {

    private final HaluMemSessionCleaner sessionCleaner;
    private final HaluMemIngestionService ingestionService;
    private final HaluMemAnsweringService answeringService;
    private final HaluMemJudgeService judgeService;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/reset")
    public HaluMemResetResponse reset(@RequestBody(required = false) HaluMemResetRequest request) {
        return sessionCleaner.reset(request == null ? new HaluMemResetRequest(java.util.List.of()) : request);
    }

    @PostMapping("/ingest-session")
    public HaluMemSessionIngestResponse ingestSession(@RequestBody HaluMemSessionIngestRequest request) {
        return ingestionService.ingest(request);
    }

    @PostMapping("/answer")
    public HaluMemAnswerResponse answer(@RequestBody HaluMemAnswerRequest request) {
        return answeringService.answer(request);
    }

    @PostMapping("/judge/memory")
    public HaluMemJudgeMemoryResponse judgeMemory(@RequestBody HaluMemJudgeMemoryRequest request) {
        return judgeService.judgeMemory(request);
    }

    @PostMapping("/judge/qa")
    public HaluMemJudgeQaResponse judgeQa(@RequestBody HaluMemJudgeQaRequest request) {
        return judgeService.judgeQa(request);
    }
}
