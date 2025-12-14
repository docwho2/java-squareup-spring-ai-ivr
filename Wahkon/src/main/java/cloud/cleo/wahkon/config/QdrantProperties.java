package cloud.cleo.wahkon.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.ai.vectorstore.qdrant")
public record QdrantProperties(
        @NotBlank
        String host,
        Integer port,
        boolean useTls,
        @NotBlank
        String apiKey,
        @NotBlank
        String collectionName
) {
    public String baseAdminUrl() {
        var scheme = useTls ? "https" : "http";
        return "%s://%s".formatted(scheme, host);
    }
}