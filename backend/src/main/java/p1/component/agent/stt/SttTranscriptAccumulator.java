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

    static String appendSegment(String currentTranscript, String nextSegment) {
        String current = normalizePartialHypothesis(currentTranscript);
        String next = normalizePartialHypothesis(nextSegment);
        if (current.isBlank()) {
            return next;
        }
        if (next.isBlank()) {
            return current;
        }
        if (current.equals(next) || current.endsWith(next) || current.contains(next)) {
            return current;
        }
        if (next.startsWith(current)) {
            return next;
        }

        int overlap = longestSuffixPrefixOverlap(current, next);
        if (overlap > 0) {
            return current + next.substring(overlap);
        }
        if (shouldJoinWithoutSpace(current.charAt(current.length() - 1), next.charAt(0))) {
            return current + next;
        }
        return current + " " + next;
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

    private static boolean shouldJoinWithoutSpace(char left, char right) {
        return isCjk(left) || isCjk(right);
    }

    private static boolean isCjk(char value) {
        Character.UnicodeScript script = Character.UnicodeScript.of(value);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
