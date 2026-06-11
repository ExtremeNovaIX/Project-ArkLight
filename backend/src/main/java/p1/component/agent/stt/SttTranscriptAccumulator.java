package p1.component.agent.stt;

final class SttTranscriptAccumulator {

    private SttTranscriptAccumulator() {
    }

    static String normalizePartialHypothesis(String text) {
        return text == null ? "" : text.trim();
    }

    static String completeTranscript(String partialHypothesis, String finalText) {
        String partial = normalizePartialHypothesis(partialHypothesis);
        String finished = normalizePartialHypothesis(finalText);
        if (partial.isBlank()) {
            return finished;
        }
        if (finished.isBlank()) {
            return partial;
        }
        if (finished.equals(partial) || finished.startsWith(partial)) {
            return finished;
        }
        if (partial.endsWith(finished) || partial.contains(finished)) {
            return partial;
        }

        int overlap = longestSuffixPrefixOverlap(partial, finished);
        if (overlap > 0) {
            return partial + finished.substring(overlap);
        }
        return partial + finished;
    }

    private static int longestSuffixPrefixOverlap(String left, String right) {
        int max = Math.min(left.length(), right.length());
        for (int length = max; length > 0; length--) {
            int leftStart = left.length() - length;
            if (left.regionMatches(leftStart, right, 0, length)) {
                return length;
            }
        }
        return 0;
    }
}
