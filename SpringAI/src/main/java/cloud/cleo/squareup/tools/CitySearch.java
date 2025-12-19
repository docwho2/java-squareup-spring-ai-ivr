package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.CityRagService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class CitySearch extends AbstractTool {

    private final CityRagService cityRagService;

    private final static long SEARCH_TIMEOUT_MS = 2500;

    @Tool(
            name = CITY_SEARCH_FUNCTION_NAME,
            description = """
            Search the City of Wahkon local website knowledge for events, schedules, ordinances, agendas, announcements, and PDFs.
            """
    )
    public CitySearchResult citySearch(
            @ToolParam(description = "The query to search the city knowledge base for.", required = true) String query,
            ToolContext ctx) {

        if (query == null || query.isBlank()) {
            return new CitySearchResult(List.of(), StatusMessageResult.Status.FAILED, "query is required and cannot be blank");
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<List<Document>> future
                = (CompletableFuture<List<Document>>) ctx.getContext().get(CityRagService.CTX_CITY_PREFETCH_FUTURE);

        List<Document> docs;

        if (future != null) {
            // Prefetch path: should be hot
            try {
                // This should be close to done, but don't wait too long for a result
                docs = future.get(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("City prefetch get timed out after {} ms, requerying", SEARCH_TIMEOUT_MS);
                docs = cityRagService.similaritySearch(query);
            } catch (ExecutionException ee) {
                log.error("City prefetch get() threw Exception", ee.getCause());
                docs = List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("City prefetch get() interrupted", e);
                docs = List.of();
            }
        } else {
            // No prefetch happened (keyword miss, etc.) → do a real search now
            docs = cityRagService.similaritySearch(query);
        }

        // Turn into Hits 
        var hits = docs.stream()
                .map(CitySearchHit::new)
                .toList();

        return new CitySearchResult(hits, StatusMessageResult.Status.SUCCESS,"Results Returned");
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return true;
    }

    public record CitySearchResult(
            List<CitySearchHit> results,
            StatusMessageResult.Status status,
            String message
            ) {

    }

    public record CitySearchHit(
            String title,
            String sourceUrl,
            String updatedAt,
            String snippet
            ) {

        public CitySearchHit(Document d) {
            this(safeString(d.getMetadata().get("title")),
                    safeString(d.getMetadata().get("sourceUrl")),
                    safeString(d.getMetadata().get("bestModifiedTs")),
                    safeSnippet(d.getText(), 450)
            );
        }
    }

    private static String safeString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String safeSnippet(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
