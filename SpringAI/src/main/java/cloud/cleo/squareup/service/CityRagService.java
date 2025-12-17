package cloud.cleo.squareup.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log4j2
public class CityRagService {

    public static final String CTX_CITY_PREFETCH_FUTURE = "cityPrefetchFuture";

    private static final int TOP_K = 4;

    private static final Pattern CITY_PREFETCH_PATTERN = Pattern.compile(
            "\\b(wahkon|city|council|ordinance|agenda|minutes|event|festival|parade|schedule|wahkon\\s+days|newsletter|trail)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final VectorStore vectorStore;
    private final ExecutorService virtualThreadExecutor;

    /**
     * Starts a a query and returns Future if pattern matches, otherwise null.
     *
     * @param inputTranscript
     * @return null if no query was started
     */
    public CompletableFuture<List<Document>> startPrefetchOrNull(String inputTranscript) {
        if (inputTranscript == null || inputTranscript.isBlank()) {
            return null;
        }

        if (!CITY_PREFETCH_PATTERN.matcher(inputTranscript).find()) {
            log.debug("City prefetch skipped due to no keyword match");
            return null;
        }

        log.debug("City prefetch started (query={})", inputTranscript);

        return CompletableFuture.supplyAsync(() -> {

            List<Document> docs = similaritySearch(inputTranscript);
            log.debug("City prefetch completed ({} docs)", docs.size());
            return docs;

        }, virtualThreadExecutor);
    }

    public List<Document> similaritySearch(String query) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(TOP_K)
                            .build()
            );
        } catch (Exception e) {
            log.error("City similaritySearch failed:", e);
            return List.of();
        }
    }
}
