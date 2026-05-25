package p1.infrastructure.markdown.codec;

import p1.component.agent.memory.model.ArchiveLink;
import p1.infrastructure.markdown.core.FrontmatterReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用于序列化和反序列化节点的links列表
 */
public final class ArchiveLinkCodec {

    private ArchiveLinkCodec() {
    }

    /**
     * 将节点的链接记录列表编码到文档的YAML头
     */
    public static List<Map<String, Object>> toFrontmatter(List<ArchiveLink> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ArchiveLink link : links) {
            if (link == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("relation", normalize(link.getRelation()));
            item.put("target_id", link.getTargetArchiveId());
            item.put("target_topic", normalize(link.getTargetTopic()));
            item.put("confidence", link.getConfidence());
            item.put("reason", normalize(link.getReason()));
            result.add(item);
        }
        return result;
    }

    public static List<ArchiveLink> fromFrontmatter(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return new ArrayList<>();
        }

        List<ArchiveLink> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            FrontmatterReader reader = FrontmatterReader.of(map);
            ArchiveLink link = new ArchiveLink();
            link.setRelation(reader.string("relation"));
            link.setTargetArchiveId(reader.longValue("target_id"));
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
