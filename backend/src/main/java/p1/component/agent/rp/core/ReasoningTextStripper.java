package p1.component.agent.rp.core;

final class ReasoningTextStripper {
    private String activeEndTag;
    private final StringBuilder reasoning = new StringBuilder();

    Result strip(String text) {
        if (text == null || text.isEmpty()) {
            return new Result("", "");
        }
        StringBuilder visible = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            if (activeEndTag != null) {
                int end = indexOfIgnoreCase(text, activeEndTag, cursor);
                if (end < 0) {
                    appendReasoning(text.substring(cursor));
                    return new Result(visible.toString(), drainReasoning());
                }
                appendReasoning(text.substring(cursor, end));
                cursor = end + activeEndTag.length();
                activeEndTag = null;
                continue;
            }

            TagMatch next = nextStartTag(text, cursor);
            if (next == null) {
                visible.append(text.substring(cursor));
                break;
            }
            visible.append(text, cursor, next.start());
            activeEndTag = next.endTag();
            cursor = next.end();
        }
        return new Result(visible.toString(), drainReasoning());
    }

    private TagMatch nextStartTag(String text, int from) {
        TagMatch think = findTag(text, from, "<think>", "</think>");
        TagMatch reasoningContent = findTag(text, from, "<reasoning_content>", "</reasoning_content>");
        if (think == null) {
            return reasoningContent;
        }
        if (reasoningContent == null) {
            return think;
        }
        return think.start() <= reasoningContent.start() ? think : reasoningContent;
    }

    private TagMatch findTag(String text, int from, String startTag, String endTag) {
        int start = indexOfIgnoreCase(text, startTag, from);
        return start < 0 ? null : new TagMatch(start, start + startTag.length(), endTag);
    }

    private int indexOfIgnoreCase(String text, String needle, int from) {
        return text.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT), from);
    }

    private void appendReasoning(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!reasoning.isEmpty()) {
            reasoning.append('\n');
        }
        reasoning.append(value.trim());
    }

    private String drainReasoning() {
        String value = reasoning.toString().trim();
        reasoning.setLength(0);
        return value;
    }

    record Result(String visibleText, String reasoningText) {
    }

    private record TagMatch(int start, int end, String endTag) {
    }
}