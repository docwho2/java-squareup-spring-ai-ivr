package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.QdrantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

@Service
@Log4j2
@RequiredArgsConstructor
public class QdrantLookupService {

    @Qualifier("qdrantAdminRestClient")
    private final RestClient qdrant;
    private final QdrantProperties props;
    private final ObjectMapper objectMapper;

    /**
     * Fetch the previously stored content_hash for (source,url) if any points exist. Uses Qdrant scroll with filter +
     * limit=1, payload only.
     *
     * @param source
     * @param url
     * @return
     */
    public Optional<String> findExistingContentHash(String source, String url) {

        log.debug("Qdrant hash lookup source={} url={}", source, url);

        var body = Map.of(
                "filter", Map.of(
                        "must", new Object[]{
                            match("source", source),
                            match("url", url)
                        }
                ),
                "limit", 1,
                "with_payload", new String[]{"content_hash"},
                "with_vector", false
        );

        try {
            String json = qdrant.post()
                    .uri("/collections/{collection}/points/scroll", props.collectionName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (json == null || json.isBlank()) {
                log.debug("Qdrant empty response source={} url={}", source, url);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode points = root.path("result").path("points");
            if (!points.isArray() || points.isEmpty()) {
                log.debug("Qdrant no points source={} url={}", source, url);
                return Optional.empty();
            }

            JsonNode payload = points.get(0).path("payload");
            JsonNode hash = payload.path("content_hash");
            if (hash.isMissingNode() || hash.isNull()) {
                log.debug("Qdrant point missing content_hash source={} url={}", source, url);
                return Optional.empty();
            }

            log.debug("Qdrant hash hit source={} url={} hash={}", source, url, hash.asText());
            return Optional.of(hash.asText());

        } catch (Exception e) {
            log.warn("Failed reading existing content_hash from Qdrant for {} {}", source, url, e);
            return Optional.empty();
        }
    }

    private static Map<String, Object> match(String key, String value) {
        return Map.of(
                "key", key,
                "match", Map.of("value", value)
        );
    }
}
