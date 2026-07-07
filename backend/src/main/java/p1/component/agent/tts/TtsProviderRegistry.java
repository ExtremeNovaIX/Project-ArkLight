package p1.component.agent.tts;

import lombok.CustomLog;
import org.springframework.stereotype.Component;

import p1.infrastructure.logging.LogDomain;
import p1.infrastructure.logging.LogOutcome;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TTS provider 注册表。
 * <p>
 * 主链路只依赖该注册表，根据 {@code tts.provider} 动态选择具体实现。新增 TTS 引擎时只需要增加
 * 一个 {@link TtsProvider} Bean，并在配置中切换 provider 名称。
 */
@Component
@CustomLog
public class TtsProviderRegistry {

    private final TtsConfig config;
    private final Map<String, TtsProvider> providers;

    public TtsProviderRegistry(TtsConfig config, List<TtsProvider> providers) {
        this.config = config;
        this.providers = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        provider -> normalize(provider.providerName()),
                        Function.identity()));
    }

    /**
     * 获取当前配置选中的 provider。
     *
     * @return provider；配置不存在时返回空
     */
    public Optional<TtsProvider> activeProvider() {
        String providerName = normalize(config.getProvider());
        TtsProvider provider = providers.get(providerName);
        if (provider == null) {
            log.warn(LogDomain.TTS, "provider.unavailable", LogOutcome.DEGRADED, "provider", config.getProvider(), "availableProviders", providers.keySet());
            return Optional.empty();
        }
        return Optional.of(provider);
    }

    private String normalize(String providerName) {
        return providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT);
    }
}
