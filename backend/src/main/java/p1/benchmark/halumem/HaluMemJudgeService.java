package p1.benchmark.halumem;

import lombok.CustomLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@CustomLog
public class HaluMemJudgeService {

    private static final int MAX_JUDGE_EXAMPLES = 3;
    private static final int MAX_PROMPT_ITEM_CHARS = 240;

    private final HaluMemMemoryJudgeAiService memoryJudgeAiService;
    private final HaluMemQaJudgeAiService qaJudgeAiService;

    public HaluMemJudgeMemoryResponse judgeMemory(HaluMemJudgeMemoryRequest request) {
        List<String> gold = sanitize(request == null ? null : request.goldMemoryPoints());
        List<String> system = sanitize(request == null ? null : request.systemMemoryItems());
        if (gold.isEmpty() && system.isEmpty()) {
            return new HaluMemJudgeMemoryResponse(0, 0, 0, 0, 1.0, 1.0, 1.0, List.of(), List.of(), "Both gold and system memories are empty.");
        }
        if (gold.isEmpty()) {
            return new HaluMemJudgeMemoryResponse(0, system.size(), 0, 0, 0.0, 1.0, 0.0, List.of(), system, "Gold memory list is empty but the system produced memories.");
        }
        if (system.isEmpty()) {
            return new HaluMemJudgeMemoryResponse(gold.size(), 0, 0, 0, 0.0, 0.0, 0.0, gold, List.of(), "System memory list is empty.");
        }

        HaluMemMemoryJudgeAiVerdict verdict = invokeMemoryJudge(gold, system);

        int matchedGoldCount = clamp(verdict.matchedGoldCount(), 0, gold.size());
        int supportedSystemCount = clamp(verdict.supportedSystemCount(), 0, system.size());
        double precision = round4((double) supportedSystemCount / system.size());
        double recall = round4((double) matchedGoldCount / gold.size());
        double f1 = precision + recall == 0.0 ? 0.0 : round4((2 * precision * recall) / (precision + recall));

        return new HaluMemJudgeMemoryResponse(
                gold.size(),
                system.size(),
                matchedGoldCount,
                supportedSystemCount,
                precision,
                recall,
                f1,
                sanitizeAndCap(verdict.missingGoldItems()),
                sanitizeAndCap(verdict.unsupportedSystemItems()),
                trimToEmpty(verdict.reasoning())
        );
    }

    public HaluMemJudgeQaResponse judgeQa(HaluMemJudgeQaRequest request) {
        HaluMemQaJudgeAiVerdict verdict = qaJudgeAiService.judge(
                trimToEmpty(request == null ? null : request.question()),
                trimToEmpty(request == null ? null : request.groundTruth()),
                trimToEmpty(request == null ? null : request.referenceContext()),
                trimToEmpty(request == null ? null : request.retrievedContext()),
                trimToEmpty(request == null ? null : request.systemAnswer())
        );
        return new HaluMemJudgeQaResponse(
                normalizeVerdict(verdict.verdict()),
                clampScore(verdict.score()),
                verdict.hallucinated() != null && verdict.hallucinated(),
                trimToEmpty(verdict.reasoning())
        );
    }

    private List<String> sanitize(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new java.util.ArrayList<>();
        for (String item : items) {
            String normalized = trimToEmpty(item);
            if (!normalized.isBlank()) {
                sanitized.add(normalized);
            }
        }
        return List.copyOf(sanitized);
    }

    private List<String> sanitizeAndCap(List<String> items) {
        List<String> sanitized = sanitize(items);
        if (sanitized.size() <= MAX_JUDGE_EXAMPLES) {
            return sanitized;
        }
        return List.copyOf(sanitized.subList(0, MAX_JUDGE_EXAMPLES));
    }

    private HaluMemMemoryJudgeAiVerdict invokeMemoryJudge(List<String> gold, List<String> system) {
        String renderedGold = HaluMemRenderSupport.renderComparableList(gold, gold.size(), MAX_PROMPT_ITEM_CHARS);
        String renderedSystem = HaluMemRenderSupport.renderComparableList(system, system.size(), MAX_PROMPT_ITEM_CHARS);
        try {
            return memoryJudgeAiService.judge(gold.size(), system.size(), renderedGold, renderedSystem);
        } catch (RuntimeException exception) {
            log.warn(LogDomain.RUNTIME, "benchmark.judge_size_mismatch", LogOutcome.DEGRADED, "goldCount", gold.size(), "systemCount", system.size(), "exception", exception.toString());
            return lexicalFallbackJudge(gold, system, exception);
        }
    }

    private HaluMemMemoryJudgeAiVerdict lexicalFallbackJudge(List<String> gold,
                                                             List<String> system,
                                                             RuntimeException exception) {
        boolean[] supportedSystemFlags = new boolean[system.size()];
        int matchedGoldCount = 0;
        List<String> missingGoldItems = new java.util.ArrayList<>();

        for (String goldItem : gold) {
            int matchedSystemIndex = findComparableSystemIndex(goldItem, system);
            if (matchedSystemIndex >= 0) {
                matchedGoldCount++;
                supportedSystemFlags[matchedSystemIndex] = true;
            } else if (missingGoldItems.size() < MAX_JUDGE_EXAMPLES) {
                missingGoldItems.add(goldItem);
            }
        }

        int supportedSystemCount = 0;
        List<String> unsupportedSystemItems = new java.util.ArrayList<>();
        for (int index = 0; index < system.size(); index++) {
            if (supportedSystemFlags[index]) {
                supportedSystemCount++;
            } else if (unsupportedSystemItems.size() < MAX_JUDGE_EXAMPLES) {
                unsupportedSystemItems.add(system.get(index));
            }
        }

        String reason = "Lexical fallback judge used after memory judge failure: "
                + exception.getClass().getSimpleName();
        return new HaluMemMemoryJudgeAiVerdict(
                reason,
                matchedGoldCount,
                supportedSystemCount,
                List.copyOf(missingGoldItems),
                List.copyOf(unsupportedSystemItems)
        );
    }

    private int findComparableSystemIndex(String goldItem, List<String> systemItems) {
        for (int index = 0; index < systemItems.size(); index++) {
            if (isComparable(goldItem, systemItems.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean isComparable(String left, String right) {
        String normalizedLeft = normalizeForMatch(left);
        String normalizedRight = normalizeForMatch(right);
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return false;
        }
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return true;
        }

        Set<String> leftTokens = tokenize(normalizedLeft);
        Set<String> rightTokens = tokenize(normalizedRight);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return false;
        }

        long sharedCount = leftTokens.stream().filter(rightTokens::contains).count();
        if (sharedCount == 0) {
            return false;
        }

        double overlapBySmaller = (double) sharedCount / Math.min(leftTokens.size(), rightTokens.size());
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        double jaccard = (double) sharedCount / union.size();
        return overlapBySmaller >= 0.6 || (sharedCount >= 3 && jaccard >= 0.3);
    }

    private Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : value.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalizeForMatch(String text) {
        return trimToEmpty(text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }

    private int clamp(Integer value, int min, int max) {
        if (value == null) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private double clampScore(Double score) {
        if (score == null) {
            return 0.0;
        }
        return round4(Math.max(0.0, Math.min(1.0, score)));
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private String normalizeVerdict(String verdict) {
        String normalized = trimToEmpty(verdict).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CORRECT", "PARTIAL", "WRONG", "HALLUCINATED", "UNKNOWN" -> normalized;
            default -> "UNKNOWN";
        };
    }

    private String trimToEmpty(String text) {
        return text == null ? "" : text.trim();
    }
}
