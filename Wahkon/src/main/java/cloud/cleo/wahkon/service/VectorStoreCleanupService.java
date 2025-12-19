package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.CrawlerProperties;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Keep the Vector store clean. Remove old items.
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class VectorStoreCleanupService {

    private final VectorStore vectorStore;
    private final CrawlerProperties props;

    public void cleanupOldVectors() {
        long cutoffEpoch = Instant.now()
                .minus(props.retentionDuration())
                .toEpochMilli();

        var b = new FilterExpressionBuilder();

        // Clean-contract: delete anything whose "best guess recency" is older than retention cutoff
        vectorStore.delete(b.lt("fetchedAtEpoch", cutoffEpoch).build());

        log.info("Vector cleanup complete: deleted docs with fetchedAtEpoch < {}", cutoffEpoch);
    }
}
