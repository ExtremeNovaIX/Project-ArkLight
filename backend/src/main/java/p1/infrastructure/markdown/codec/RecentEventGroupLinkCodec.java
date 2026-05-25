package p1.infrastructure.markdown.codec;

import p1.component.agent.memory.model.RecentEventGroupLinkRecord;
import p1.infrastructure.markdown.core.FrontmatterReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用于序列化和反序列化节点的recent_event_group_links列表
 */
public final class RecentEventGroupLinkCodec {

    private RecentEventGroupLinkCodec() {
    }

    public static List<Map<String, Object>> toFrontmatter(List<RecentEventGroupLinkRecord> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (RecentEventGroupLinkRecord link : links) {
            if (link == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("relation", normalize(link.getRelation()));
            item.put("target_group_id", normalize(link.getTargetGroupId()));
            item.put("target_topic", normalize(link.getTargetTopic()));
            item.put("confidence", link.getConfidence());
            item.put("reason", normalize(link.getReason()));
            result.add(item);
        }
        return result;
    }

    public static List<RecentEventGroupLinkRecord> fromFrontmatter(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return new ArrayList<>();
        }

        List<RecentEventGroupLinkRecord> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            FrontmatterReader reader = FrontmatterReader.of(map);
            RecentEventGroupLinkRecord link = new RecentEventGroupLinkRecord();
            link.setRelation(reader.string("relation"));
            link.setTargetGroupId(reader.string("target_group_id"));
            link.setTargetTopic(reader.string("target_topic"));
            link.setConfidence(reader.doubleValue("confidence"));
            link.setReason(reader.string("reason"));
            result.add(link);
        }
        return result;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
