package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log4j2
public class CitySearch extends AbstractTool {

    private final VectorStore vectorStore;

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
            return new CitySearchResult(List.of(), "query is required and cannot be blank", false);
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<List<org.springframework.ai.document.Document>> fut
                = (CompletableFuture<List<org.springframework.ai.document.Document>>) ctx.getContext().get("cityPrefetch");

        List<org.springframework.ai.document.Document> docs;

        if (fut != null) {
            // Prefetch path: should be hot
            try {
                docs = fut.join();
            } catch (Exception e) {
                log.warn("city_search join failed: {}", e.toString());
                docs = List.of();
            }
        } else {
            // No prefetch happened (keyword miss, etc.) → do the real search now
            docs = runCitySearch(query);
        }

        var hits = docs.stream().limit(4).map(d -> new CitySearchHit(
                safeString(d.getMetadata().get("title")),
                safeString(d.getMetadata().get("url")),
                safeSnippet(d.getText(), 450)
        )).toList();

        return new CitySearchResult(hits, "ok", true);
    }

    private List<org.springframework.ai.document.Document> runCitySearch(String q) {
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(q)
                            .topK(4)
                            .build()
            );
        } catch (Exception e) {
            log.warn("city_search live query failed: {}", e.toString());
            return List.of();
        }
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return true;
    }

    public record CitySearchResult(
            List<CitySearchHit> results,
            String status,
            boolean success
            ) {

    }

    public record CitySearchHit(
            String title,
            String url,
            String snippet
            ) {

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
