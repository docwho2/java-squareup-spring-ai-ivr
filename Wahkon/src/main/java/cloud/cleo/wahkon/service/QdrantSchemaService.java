package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.QdrantProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Called at startup to ensure payload indexes exist (idempotent).
 * Helps performance for filters/range queries on metadata fields.
 */
@Service
@Log4j2
public class QdrantSchemaService {

    @Autowired
    @Qualifier("qdrantAdminRestClient")
    private RestClient qdrantRestClient;

    @Autowired
    private QdrantProperties props;

    public void ensurePayloadIndexes() {
        String collection = props.collectionName();

        // Equality filters you rely on everywhere
        ensureKeywordIndexes(collection, List.of(
                "sourceSystem",
                "sourceUrl",
                "kind"
        ));

        // Range / recency filters:
        // Prefer numeric epoch rather than ISO strings for Qdrant range queries.
        ensureIntegerIndex(collection, "bestModifiedTsEpoch");
        ensureIntegerIndex(collection, "fetchedAtEpoch");

        // Optional: if you decide to store these too
        // ensureIntegerIndex(collection, "bestPublishedTsEpoch");
        // ensureIntegerIndex(collection, "fetchedAtEpoch");
    }

    private void ensureKeywordIndexes(String collection, List<String> fieldNames) {
        for (String field : fieldNames) {
            ensureIndex(
                    collection,
                    field,
                    Map.of(
                            "field_name", field,
                            "field_schema", "keyword"
                    )
            );
        }
    }

    private void ensureIntegerIndex(String collection, String fieldName) {
        // Some Qdrant versions accept just "integer" as a string; others prefer the object schema.
        // The object form tends to be forward-compatible.
        ensureIndex(
                collection,
                fieldName,
                Map.of(
                        "field_name", fieldName,
                        "field_schema", Map.of("type", "integer")
                )
        );
    }

    private void ensureIndex(String collection, String label, Map<String, Object> body) {
        try {
            qdrantRestClient.put()
                    .uri("/collections/{collection}/index", collection)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Qdrant payload index ensured: {}.{}", collection, label);

        } catch (HttpClientErrorException e) {
            // If index already exists, treat as success (idempotent)
            // Depending on Qdrant version/config, this can come back as 409 or sometimes 400.
            HttpStatus sc = (HttpStatus) e.getStatusCode();
            if (sc == HttpStatus.CONFLICT || sc == HttpStatus.BAD_REQUEST) {
                log.debug("Qdrant index likely already exists: {}.{} ({})", collection, label, sc);
                return;
            }
            throw e;
        }
    }
}
