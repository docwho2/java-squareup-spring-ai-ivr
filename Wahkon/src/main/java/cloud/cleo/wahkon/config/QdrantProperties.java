package cloud.cleo.wahkon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.ai.vectorstore.qdrant")
public record QdrantProperties(
        String host,
        Integer port,
        boolean useTls,
        String apiKey,
        String collectionName
) {
    public String baseAdminUrl() {
        var scheme = useTls ? "https" : "http";
        return "%s://%s".formatted(scheme, host);
    }
}