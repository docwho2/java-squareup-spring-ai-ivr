package cloud.cleo.wahkon.cloudfunctions;

import cloud.cleo.wahkon.service.FacebookPipelineService;
import cloud.cleo.wahkon.service.QdrantSchemaService;
import cloud.cleo.wahkon.service.VectorStoreCleanupService;
import cloud.cleo.wahkon.service.WahkonWebCrawlerService;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

/**
 *
 * @author sjensen
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class CloudWatchFunction implements Function<byte[], Void> {

    private final WahkonWebCrawlerService crawler;
    private final FacebookPipelineService facebookPipelineService;
    private final QdrantSchemaService qDrant;
    private final VectorStoreCleanupService vectorStoreCleanupService;
    private final Executor executor; 
    
    @Override
    public Void apply(byte[] ignored) {
        log.info("Crawler triggered by EventBridge");
        
        // Ensure indexes are always created (in case store is reset)
        qDrant.ensurePayloadIndexes();
        
        var tasks = new ArrayList<CompletableFuture<Void>>(3);

        tasks.add(CompletableFuture.runAsync(() -> {
            log.info("Starting Facebook ingest pipeline");
            facebookPipelineService.ingestAllConfiguredPages();
            log.info("Finished Facebook ingest pipeline");
        }, executor));

        tasks.add(CompletableFuture.runAsync(() -> {
            log.info("Starting Web crawl pipeline");
            crawler.crawlAll();
            log.info("Finished Web crawl pipeline");
        }, executor));

        tasks.add(CompletableFuture.runAsync(() -> {
            log.info("Starting Vector Store Cleanup");
            vectorStoreCleanupService.cleanupOldVectors();
            log.info("Finished Vector Store Cleanup");
        }, executor));

        
        // Wait for both; if either fails, surface a useful error (so Lambda reports failure).
        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException ce) {
            // log the underlying cause cleanly
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            log.error("One or more pipelines failed", cause);
        }
        
        return null;
    }
    
}
