package cloud.cleo.wahkon.cloudfunctions;

import cloud.cleo.wahkon.cloudfunctions.ScheduledFunction.ScheduleInput;
import cloud.cleo.wahkon.service.FacebookPipelineService;
import cloud.cleo.wahkon.service.QdrantSchemaService;
import cloud.cleo.wahkon.service.VectorStoreCleanupService;
import cloud.cleo.wahkon.service.WahkonWebCrawlerService;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class ScheduledFunction implements Function<ScheduleInput, Void> {

    private final WahkonWebCrawlerService crawler;
    private final FacebookPipelineService facebookPipelineService;
    private final QdrantSchemaService qDrant;
    private final VectorStoreCleanupService vectorStoreCleanupService;
    private final Executor executor;

    public record ScheduleInput(String period) {

    }

    private enum Period {
        DAILY, HOURLY, ALL;

        static Period from(String raw) {
            if (raw == null || raw.isBlank()) {
                return ALL;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "daily" ->
                    DAILY;
                case "hourly" ->
                    HOURLY;
                case "all" ->
                    ALL;
                default -> {
                    log.warn("Unknown period '{}'; defaulting to ALL", raw);
                    yield ALL;
                }
            };
        }
    }

    @Override
    public Void apply(ScheduleInput input) {
        final Period period = Period.from(input.period());

        log.info("Crawler triggered by EventBridge (period={})", period);

        // Always ensure indexes (safe + fast; helps if store/reset happened)
        qDrant.ensurePayloadIndexes();

        var tasks = new ArrayList<CompletableFuture<Void>>(3);

        // FB hourly (and in ALL)
         switch (period) {
            case HOURLY, ALL ->
            tasks.add(CompletableFuture.runAsync(() -> {
                log.info("Starting Facebook ingest pipeline");
                facebookPipelineService.ingestAllConfiguredPages();
                log.info("Finished Facebook ingest pipeline");
            }, executor));
        }

        // Web daily (and in ALL)
         switch (period) {
            case DAILY, ALL ->
            tasks.add(CompletableFuture.runAsync(() -> {
                log.info("Starting Web crawl pipeline");
                crawler.crawlAll();
                log.info("Finished Web crawl pipeline");
            }, executor));
        }

        // Cleanup: recommend daily (and ALL), not hourly
        switch (period) {
            case DAILY, ALL ->
                tasks.add(CompletableFuture.runAsync(() -> {
                    log.info("Starting Vector Store Cleanup");
                    vectorStoreCleanupService.cleanupOldVectors();
                    log.info("Finished Vector Store Cleanup");
                }, executor));
        }

        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
            log.info("All requested pipelines completed (period={})", period);
            return null;
        } catch (CompletionException ce) {
            Throwable cause = (ce.getCause() != null) ? ce.getCause() : ce;
            log.error("One or more pipelines failed (period={})", period, cause);

            // IMPORTANT: rethrow so Lambda marks the invocation failed
            throw new RuntimeException("Crawler failed (period=" + period + ")", cause);
        }
    }
}
