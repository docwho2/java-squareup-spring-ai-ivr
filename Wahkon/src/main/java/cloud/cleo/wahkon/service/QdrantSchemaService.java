package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.QdrantProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;


/**
 * Should be called at startup to ensure indexes are all created in
 * case the store was reset.
 * 
 * @author sjensen
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
        // Fields you use in filters/deletes:
        ensureIndex("source", Map.of("field_name", "source", "field_schema", "keyword"));
        ensureIndex("url", Map.of("field_name", "url", "field_schema", "keyword"));

        // For range delete: lt("crawled_at_epoch", cutoff)
        // Qdrant supports richer schema objects too; "integer" is the core idea.
        ensureIndex("crawled_at_epoch", Map.of(
                "field_name", "crawled_at_epoch",
                "field_schema", Map.of("type", "integer", "range", true)
        ));
    }

    private void ensureIndex(String label, Map<String, Object> body) {
        String collection = props.collectionName();

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
            // Exact status can vary by version/config; some setups return 409 Conflict.
            if (e.getStatusCode() == HttpStatus.CONFLICT || e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                log.debug("Qdrant index likely already exists: {}.{} ({})",
                        collection, label, e.getStatusCode());
                return;
            }
            throw e;
        }
    }
}