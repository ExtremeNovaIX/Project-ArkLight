package p1.component.agent.rp.core;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class CharacterPromptRegistry {

    private static final String PROMPT_FILE_NAME = "prompt.txt";

    /**
     * 角色目录配置。发布包会通过 ARCLIGHT_CHARA_DIR 指向包根目录下的 chara。
     */
    @Value("${assistant.rp.character-directory:${ARCLIGHT_CHARA_DIR:}}")
    private String configuredCharacterDirectory;

    @Getter
    private Map<String, String> prompts = Map.of();

    /**
     * 启动时加载所有角色 prompt。
     * <p>
     * 目录解析优先级：显式配置/环境变量、当前工作目录下的 chara、旧版源码运行时的父目录 chara。
     */
    @PostConstruct
    public void loadPrompts() {
        Path charaDirectory = resolveCharaDirectory();

        if (charaDirectory == null || !Files.isDirectory(charaDirectory)) {
            throw new IllegalStateException("Character directory not found: " + charaDirectory);
        }

        Map<String, String> loadedPrompts = new LinkedHashMap<>();
        try (Stream<Path> directories = Files.list(charaDirectory)) {
            directories
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(characterDirectory -> {
                        Path promptPath = characterDirectory.resolve(PROMPT_FILE_NAME);
                        if (!Files.isRegularFile(promptPath)) {
                            throw new IllegalStateException("Missing prompt file: " + promptPath);
                        }

                        try {
                            String prompt = Files.readString(promptPath, StandardCharsets.UTF_8).trim();
                            if (prompt.isBlank()) {
                                throw new IllegalStateException("Prompt file is blank: " + promptPath);
                            }
                            loadedPrompts.put(characterDirectory.getFileName().toString(), prompt);
                        } catch (IOException e) {
                            throw new IllegalStateException("Failed to read prompt file: " + promptPath, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan character directory: " + charaDirectory, e);
        }

        if (loadedPrompts.isEmpty()) {
            throw new IllegalStateException("No characters found under: " + charaDirectory);
        }

        this.prompts = Map.copyOf(loadedPrompts);
    }

    /**
     * 根据当前运行环境寻找角色目录。
     *
     * @return 找到的角色目录；找不到时返回最后一个候选目录，便于错误日志定位
     */
    private Path resolveCharaDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();

        if (configuredCharacterDirectory != null && !configuredCharacterDirectory.isBlank()) {
            Path configured = Path.of(configuredCharacterDirectory.trim());
            candidates.add(configured.isAbsolute()
                    ? configured.normalize()
                    : workingDirectory.resolve(configured).normalize());
        }

        candidates.add(workingDirectory.resolve("chara").normalize());
        if (workingDirectory.getParent() != null) {
            candidates.add(workingDirectory.getParent().resolve("chara").normalize());
        }

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        return candidates.isEmpty() ? null : candidates.get(candidates.size() - 1);
    }

    /**
     * 读取指定角色的 prompt。
     *
     * @param characterName 角色目录名
     * @return 角色 prompt 正文
     */
    public String getPrompt(String characterName) {
        if (characterName == null || characterName.isBlank()) {
            throw new IllegalArgumentException("characterName is required");
        }

        String prompt = prompts.get(characterName);
        if (prompt == null) {
            throw new IllegalArgumentException("Unknown characterName: " + characterName);
        }
        return prompt;
    }
}
