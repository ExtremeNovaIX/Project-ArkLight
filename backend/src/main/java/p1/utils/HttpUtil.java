package p1.utils;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import lombok.NonNull;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

public class HttpUtil {
    private static final Set<String> LOCAL_HOSTS = Set.of(
            "127.0.0.1",
            "localhost",
            "0:0:0:0:0:0:0:1",
            "::1"
    );

    /**
     * 判断是否为本地端点
     */
    public static boolean isLocalEndpoint(@NonNull String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static JdkHttpClientBuilder getLocalClientBuilder(Duration readTimeout) {
        return new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NORMAL))
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(readTimeout);
    }
}
