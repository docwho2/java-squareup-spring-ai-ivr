package cloud.cleo.wahkon.indexer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

//@Component
public class QdrantPayloadIndexInitializer implements CommandLineRunner {

    private final RestClient rest;
    private final String collection;

    public QdrantPayloadIndexInitializer(
            @Value("${spring.ai.vectorstore.qdrant.host}") String host,
            @Value("${spring.ai.vectorstore.qdrant.use-tls:true}") boolean useTls,
            @Value("${spring.ai.vectorstore.qdrant.api-key}") String apiKey,
            @Value("${spring.ai.vectorstore.qdrant.collection-name}") String collection
    ) {
        // Qdrant Cloud dashboard endpoint is HTTPS on 443 (no port).
        String baseUrl = (useTls ? "https://" : "http://") + host;

        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("api-key", apiKey)
                .build();

        this.collection = collection;
    }

    @Override
    public void run(String... args) {
        ensureIndex("source", Map.of("type", "keyword"));
        ensureIndex("url", Map.of("type", "keyword"));
        ensureIndex("crawled_at_epoch", Map.of("type", "integer"));
    }

    private void ensureIndex(String fieldName, Map<String, Object> fieldSchema) {
        try {
            rest.put()
                .uri("/collections/{c}/index?wait=true", collection)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("field_name", fieldName, "field_schema", fieldSchema))
                .retrieve()
                .toBodilessEntity();
        } catch (Exception ignored) {
            // "ensure exists" semantics; if it already exists, we don't care.
        }
    }
}
