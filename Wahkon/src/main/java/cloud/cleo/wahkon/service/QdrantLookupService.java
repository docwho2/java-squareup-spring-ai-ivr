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
     * Fetch the previously stored contentSha256 for (source,url) if any points exist. Uses Qdrant scroll with filter +
     * limit=1, payload only.
     *
     * @param sourceSystem
     * @param sourceUrl
     * @return
     */
    public Optional<String> findExistingContentSha256(String sourceSystem, String sourceUrl) {

        //log.debug("Qdrant hash lookup source={} url={}", source, url);

        var body = Map.of(
                "filter", Map.of(
                        "must", new Object[]{
                            match("sourceSystem", sourceSystem),
                            match("sourceUrl", sourceUrl)
                        }
                ),
                "limit", 1,
                "with_payload", new String[]{"contentSha256"},
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
                log.debug("Qdrant empty response source={} url={}", sourceSystem, sourceUrl);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode points = root.path("result").path("points");
            if (!points.isArray() || points.isEmpty()) {
                log.debug("Qdrant no points source={} url={}", sourceSystem, sourceUrl);
                return Optional.empty();
            }

            JsonNode payload = points.get(0).path("payload");
            JsonNode hash = payload.path("contentSha256");
            if (hash.isMissingNode() || hash.isNull() || hash.asText().isBlank()) {
                log.debug("Qdrant point missing contentSha256 source={} url={}", sourceSystem, sourceUrl);
                return Optional.empty();
            }

            log.debug("Qdrant hash hit source={} url={} hash={}", sourceSystem, sourceUrl, hash.asText());
            return Optional.of(hash.asText());

        } catch (Exception e) {
            log.warn("Failed reading existing contentSha256 from Qdrant for {} {}", sourceSystem, sourceUrl, e);
            return Optional.empty();
        }
    }
    
    /**
     * Just updated the crawled timestamps.
     * 
     * @param sourceSystem
     * @param sourceUrl
     * @param crawledAt 
     */
    public void touchCrawled(String sourceSystem, String sourceUrl, Instant crawledAt) {
        var payload = Map.of(
                "fetchedAt", crawledAt.toString(),
                "fetchedAtEpoch", crawledAt.toEpochMilli()
        );

        var body = Map.of(
                "payload", payload,
                "filter", Map.of(
                        "must", new Object[]{
                                match("sourceSystem", sourceSystem),
                                match("sourceUrl", sourceUrl)
                        }
                )
        );

        qdrant.post()
                .uri("/collections/{collection}/points/payload", props.collectionName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Touched payload for {} {}", sourceSystem, sourceUrl);
    }


    private static Map<String, Object> match(String key, String value) {
        return Map.of(
                "key", key,
                "match", Map.of("value", value)
        );
    }
}
