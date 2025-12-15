/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.CrawlerProperties;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Keep the Vector store clean.  Remove old items.
 * 
 * @author sjensen
 */
@Service
@Log4j2
@RequiredArgsConstructor
public class VectorStoreCleanupService {
    private final VectorStore vectorStore;
    private final CrawlerProperties props;
    
    
     public void cleanupOldVectors() {
        var cutoff = Instant.now().minus(props.retentionDuration()).toEpochMilli();
        var b = new FilterExpressionBuilder();
        vectorStore.delete(b.lt("crawled_at_epoch", cutoff).build());
    }
    
}
