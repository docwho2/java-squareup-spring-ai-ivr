package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.QdrantProperties;

import java.time.Instant;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Log4j2
public class QdrantLookupService {

    @Autowired
    @Qualifier("qdrantAdminRestClient")
    private RestClient qdrant;
    
    @Autowired
    private QdrantProperties props;
    
    @Autowired
    private JsonMapper objectMapper;

    
    
    /**
     * Fetch the previously stored content_hash for (source,url) if any points exist. Uses Qdrant scroll with filter +
     * limit=1, payload only.
     *
     * @param source
     * @param url
     * @return
     */
    public Optional<String> findExistingContentHash(String source, String url) {

        //log.debug("Qdrant hash lookup source={} url={}", source, url);

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
            ResponseEntity<String> resp = qdrant.post()
                    .uri("/collections/{collection}/points/scroll", props.collectionName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            
            String json = resp.getBody();
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
    
    /**
     * Just updated the crawled timestamps.
     * 
     * @param source
     * @param url
     * @param crawledAt 
     */
    public void touchCrawled(String source, String url, Instant crawledAt) {
        var payload = Map.of(
                "crawled_at", crawledAt.toString(),
                "crawled_at_epoch", crawledAt.toEpochMilli()
        );

        var body = Map.of(
                "payload", payload,
                "filter", Map.of(
                        "must", new Object[]{
                                match("source", source),
                                match("url", url)
                        }
                )
        );

        qdrant.post()
                .uri("/collections/{collection}/points/payload", props.collectionName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Touched payload for {} {}", source, url);
    }


    private static Map<String, Object> match(String key, String value) {
        return Map.of(
                "key", key,
                "match", Map.of("value", value)
        );
    }
}
