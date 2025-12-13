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
    public String baseUrl() {
        var scheme = useTls ? "https" : "http";

        // If port is null, omit it (lets you use managed endpoints on 443 without specifying port)
        if (port == null) {
            return "%s://%s".formatted(scheme, host);
        }
        return "%s://%s:%d".formatted(scheme, host, port);
    }
}