package p1.infrastructure.markdown;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import p1.config.prop.AssistantProperties;
import p1.infrastructure.markdown.assembler.MemoryArchiveMdAssembler;
import p1.infrastructure.markdown.model.MarkdownDocument;
import p1.model.document.MemoryArchiveDocument;
import p1.service.markdown.MemoryArchiveStore;
import p1.utils.SessionUtil;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class MarkdownMemoryArchiveStore implements MemoryArchiveStore {

    private final AssistantProperties props;
    private final MarkdownFileAccess fileAccess;
    private final MemoryArchiveMdAssembler mapper;

    private final ConcurrentHashMap<Long, ArchiveLocation> locations = new ConcurrentHashMap<>();
    private final Object indexLock = new Object();
    private final AtomicBoolean indexLoaded = new AtomicBoolean(false);
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public boolean existsById(Long id) {
        ensureIndexLoaded();
        return id != null && locations.containsKey(id);
    }

    @Override
    public Optional<MemoryArchiveDocument> findById(Long id) {
        ensureIndexLoaded();
        if (id == null) {
            return Optional.empty();
        }

        ArchiveLocation location = locations.get(id);
        if (location == null) {
            return Optional.empty();
        }

        return fileAccess.readOptional(location.path(), "memory archive markdown").map(mapper::fromMarkdown);
    }

    @Override
    public List<MemoryArchiveDocument> findAllOrderByIdAsc() {
        ensureIndexLoaded();
        return locations.keySet().stream()
                .sorted()
                .map(this::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<MemoryArchiveDocument> findAllOrderByIdAsc(String sessionId) {
        ensureIndexLoaded();
        String normalizedSessionId = SessionUtil.normalizeSessionId(sessionId);
        return locations.entrySet().stream()
                .filter(entry -> normalizedSessionId.equals(entry.getValue().sessionId()))
                .map(Map.Entry::getKey)
                .sorted()
                .map(this::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public MemoryArchiveDocument save(MemoryArchiveDocument archive) {
        ensureIndexLoaded();

        boolean isNew = archive.getId() == null;
        LocalDateTime now = LocalDateTime.now();
        archive.setSessionId(SessionUtil.normalizeSessionId(archive.getSessionId()));
        archive.setGroupId(normalize(archive.getGroupId()));
        archive.setTopic(normalize(archive.getTopic()));
        archive.setKeywordSummary(normalize(archive.getKeywordSummary()));
        archive.setNarrative(normalize(archive.getNarrative()));
        archive.setEventGraph(normalize(archive.getEventGraph()));
        archive.setEventGraphTrace(normalize(archive.getEventGraphTrace()));
        archive.setGroupTags(normalizeTags(archive.getGroupTags()));

        if (isNew) {
            archive.setId(sequence.incrementAndGet());
            archive.setCreatedAt(now);
            archive.setUpdatedAt(now);
        } else {
            if (archive.getCreatedAt() == null) {
                archive.setCreatedAt(now);
            }
            archive.setUpdatedAt(now);
        }

        ArchiveLocation currentLocation = locations.get(archive.getId());
        Path targetPath = resolveTargetPath(archive, currentLocation);
        MarkdownDocument document = mapper.toMarkdown(archive, buildRenderedLinks(archive));
        fileAccess.write(targetPath, document, "memory archive markdown");

        if (currentLocation != null && !currentLocation.path().equals(targetPath)) {
            fileAccess.deleteIfExists(currentLocation.path(), "memory archive markdown");
        }

        locations.put(archive.getId(), new ArchiveLocation(archive.getSessionId(), targetPath));
        return archive;
    }

    @Override
    public String noteId(Long id) {
        return mapper.noteId(id);
    }

    @Override
    public String displayTitle(MemoryArchiveDocument archive) {
        return mapper.buildTitle(archive);
    }

    @Override
    public String relativeNotePath(MemoryArchiveDocument archive) {
        ensureIndexLoaded();
        if (archive == null || archive.getId() == null) {
            return "";
        }

        ArchiveLocation location = locations.get(archive.getId());
        if (location == null) {
            return "";
        }

        Path root = Paths.get(props.getMdRepository().getPath()).toAbsolutePath().normalize();
        return root.relativize(location.path().toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/')
                .replaceAll("\\.md$", "");
    }

    private void ensureIndexLoaded() {
        if (indexLoaded.get()) {
            return;
        }

        synchronized (indexLock) {
            if (indexLoaded.get()) {
                return;
            }

            Map<Long, ArchiveLocation> loadedLocations = new LinkedHashMap<>();
            long maxId = 0;
            for (Path path : listAllPaths()) {
                MarkdownDocument document = fileAccess.readOptional(path, "memory archive markdown").orElse(null);
                if (document == null) {
                    continue;
                }

                MemoryArchiveDocument archive = mapper.fromMarkdown(document);
                if (archive.getId() == null) {
                    continue;
                }

                loadedLocations.put(archive.getId(), new ArchiveLocation(
                        SessionUtil.normalizeSessionId(archive.getSessionId()),
                        path
                ));
                maxId = Math.max(maxId, archive.getId());
            }

            locations.clear();
            locations.putAll(loadedLocations);
            sequence.set(maxId);
            indexLoaded.set(true);
        }
    }

    private Path resolveTargetPath(MemoryArchiveDocument archive, ArchiveLocation currentLocation) {
        String fileStem = sanitizeFileStem(mapper.buildTitle(archive));
        Path baseDir = Paths.get(props.getMdRepository().getPath(), "sessions", archive.getSessionId(), "wiki", "memories");

        int suffix = 1;
        while (true) {
            String candidateStem = suffix == 1 ? fileStem : fileStem + "-" + suffix;
            Path candidatePath = baseDir.resolve(candidateStem + ".md");
            if (currentLocation != null && currentLocation.path().equals(candidatePath)) {
                return candidatePath;
            }
            if (isPathAvailable(candidatePath, archive.getId())) {
                return candidatePath;
            }
            suffix++;
        }
    }

    private boolean isPathAvailable(Path candidatePath, Long archiveId) {
        String normalizedCandidate = candidatePath.normalize().toString();
        return locations.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(archiveId))
                .map(entry -> entry.getValue().path().normalize().toString())
                .noneMatch(normalizedCandidate::equals);
    }

    private String sanitizeFileStem(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "untitled-memory";
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|#\\[\\]]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "untitled-memory";
        }
        return normalized.length() > 80 ? normalized.substring(0, 80).trim() : normalized;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String tag : tags) {
            String value = normalize(tag);
            if (!value.isBlank() && !normalized.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private List<MemoryArchiveMdAssembler.RenderedArchiveLink> buildRenderedLinks(MemoryArchiveDocument archive) {
        if (archive == null || archive.getLinks() == null || archive.getLinks().isEmpty()) {
            return List.of();
        }

        Path root = Paths.get(props.getMdRepository().getPath()).toAbsolutePath().normalize();
        List<MemoryArchiveMdAssembler.RenderedArchiveLink> renderedLinks = new ArrayList<>();
        archive.getLinks().forEach(link -> {
            if (link == null || link.getTargetArchiveId() == null) {
                return;
            }

            ArchiveLocation targetLocation = locations.get(link.getTargetArchiveId());
            String targetPath = targetLocation == null
                    ? mapper.noteId(link.getTargetArchiveId())
                    : toRelativeNotePath(root, targetLocation.path());
            String targetLabel = targetLocation == null
                    ? mapper.noteId(link.getTargetArchiveId())
                    : stripMarkdownExtension(targetLocation.path().getFileName().toString());

            String wikilink = "[[" + targetPath + "|" + targetLabel + "]]";
            renderedLinks.add(new MemoryArchiveMdAssembler.RenderedArchiveLink(
                    normalize(link.getRelation()),
                    wikilink,
                    normalize(link.getReason())
            ));
        });
        return renderedLinks;
    }

    private String toRelativeNotePath(Path root, Path absolutePath) {
        return root.relativize(absolutePath.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/')
                .replaceAll("\\.md$", "");
    }

    private String stripMarkdownExtension(String filename) {
        return filename == null ? "" : filename.replaceFirst("\\.md$", "");
    }

    private List<Path> listAllPaths() {
        Path root = Paths.get(props.getMdRepository().getPath());
        return fileAccess.listRegularFiles(root, "memory archive markdown files").stream()
                .filter(this::isArchivePath)
                .toList();
    }

    private boolean isArchivePath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/wiki/memories/");
    }

    private record ArchiveLocation(String sessionId, Path path) {
    }
}
