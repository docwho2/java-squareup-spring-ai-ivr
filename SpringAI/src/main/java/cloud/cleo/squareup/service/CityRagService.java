package cloud.cleo.squareup.service;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.enums.Language;
import java.time.Instant;
import java.util.Comparator;
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

    // How many hits to return to the model
    private static final int TOP_K = 4;

    // Pull extra candidates, then re-rank by bestModifiedTsEpoch / bestModifiedTs
    private static final int CANDIDATE_K = 30;

    private static final Pattern CITY_PREFETCH_PATTERN = Pattern.compile(
            "\\b(wahkon|city|council|ordinance|agenda|minutes|event|festival|parade|schedule|wahkon\\s+days|newsletter|trail)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final VectorStore vectorStore;
    private final ExecutorService virtualThreadExecutor;

    public CompletableFuture<List<Document>> startPrefetchOrNull(LexV2EventWrapper eventWrapper) {
        // For a pre-fetch it must be english language, other languages will need translation first
        if ( ! eventWrapper.getLocale().equals(Language.English.getLocale()) ) {
            log.debug("City prefetch skipped due to not being english language");
            return null;
        }
        
        final var inputTranscript = eventWrapper.getInputTranscript();
        
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
            List<Document> candidates = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(CANDIDATE_K)
                            .build()
            );

            if (candidates.isEmpty()) {
                return List.of();
            }

            // Re-rank by recency using metadata, then take TOP_K.
            // Prefer bestModifiedTsEpoch (long). Fallback to bestModifiedTs (ISO string).
            return candidates.stream()
                    .sorted(Comparator.comparingLong(CityRagService::bestModifiedEpochSafe).reversed())
                    .limit(TOP_K)
                    .toList();

        } catch (Exception e) {
            log.error("City similaritySearch failed:", e);
            return List.of();
        }
    }

    private static long bestModifiedEpochSafe(Document d) {
        if (d == null || d.getMetadata() == null) {
            return 0L;
        }

        Object epoch = d.getMetadata().get("bestModifiedTsEpoch");
        if (epoch instanceof Number n) {
            return n.longValue();
        }
        if (epoch instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (Exception ignored) {
            }
        }

        Object iso = d.getMetadata().get("bestModifiedTs");
        if (iso instanceof String s) {
            try {
                return Instant.parse(s.trim()).toEpochMilli();
            } catch (Exception ignored) {
            }
        }

        // As a last resort, fall back to fetchedAt
        Object fetched = d.getMetadata().get("fetchedAt");
        if (fetched instanceof String s) {
            try {
                return Instant.parse(s.trim()).toEpochMilli();
            } catch (Exception ignored) {
            }
        }

        return 0L;
    }
}