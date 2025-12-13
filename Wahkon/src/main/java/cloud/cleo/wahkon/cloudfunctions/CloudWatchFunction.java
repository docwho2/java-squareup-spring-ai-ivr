package cloud.cleo.wahkon.cloudfunctions;

import cloud.cleo.wahkon.service.QdrantSchemaService;
import cloud.cleo.wahkon.service.WahkonWebCrawlerService;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
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
public class CloudWatchFunction implements Function<ScheduledEvent, Void> {

    private final WahkonWebCrawlerService crawler;
    private final QdrantSchemaService qDrant;
    
    @Override
    public Void apply(ScheduledEvent event) {
        log.info("Crawler triggered by EventBridge at {}", event.getTime());
        
        // Ensure indexes are always created (in case store is reset)
        qDrant.ensurePayloadIndexes();
        
        // Run all services needed (Web and Facebook, etc..)
        
        crawler.crawlAll();
        
        return null;
    }
    
}
