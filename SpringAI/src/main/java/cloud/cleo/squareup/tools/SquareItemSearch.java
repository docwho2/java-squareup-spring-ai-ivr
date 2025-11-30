package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.squareup.square.types.SearchCatalogItemsRequest;
import com.squareup.square.types.SearchCatalogItemsResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Search for items based on search query using virtual threads.
 */
@Component
public class SquareItemSearch extends AbstractTool {

    @Tool(
            name = "store_product_item",
            description = """
            Search for store product items by text and return up to 5 item names. \
            The result may not include all matching items if more than 5 exist.
            """
    )
    public SquareItemSearchResult searchItems(SquareItemSearchRequest r, ToolContext ctx) {

        if (r == null || r.searchText == null || r.searchText.isBlank()) {
            log.debug("Empty or null input detected on Square Item search tool");
            return new SquareItemSearchResult(
                    List.of(),
                    "FAILED",
                    "search_text is required to search inventory"
            );
        }

        log.debug("Square Item Search for {}", r.searchText);
        List<String> tokens = allCombinations(r.searchText);
        List<String> itemNames = new ArrayList<>();

        log.debug("Launching {} item searches in parallel using virtual threads", tokens.size());

        try {
            // Use the shared virtual-thread executor from AbstractTool
            List<Future<SearchCatalogItemsResponse>> futures = tokens.stream()
                    .map(token -> VIRTUAL_THREAD_EXECUTOR.submit(() -> {
                log.debug("Executing search for [{}]", token);
                return getSquareClient()
                        .catalog()
                        .searchItems(SearchCatalogItemsRequest.builder()
                                .textFilter(token)
                                .limit(5)
                                .build())
                        .join(); // block only inside virtual thread
            }))
                    .collect(Collectors.toList());

            // Collect results
            for (Future<SearchCatalogItemsResponse> future : futures) {
                try {
                    SearchCatalogItemsResponse response = future.get(); // blocking in virtual thread
                    if (response.getItems() != null && response.getItems().isPresent()) {
                        response.getItems().get().stream()
                                .map(item -> item.getItem().get()
                                .getItemData().get()
                                .getName().get())
                                .forEach(itemNames::add);
                    }
                } catch (Exception e) {
                    log.error("Error processing search request", e);
                }
            }

        } catch (Exception ex) {
            log.error("Unhandled error in Square item search", ex);
            return new SquareItemSearchResult(
                    List.of(),
                    "FAILED",
                    ex.getLocalizedMessage()
            );
        }

        // De-duplicate and limit to 5 overall
        List<String> distinct = itemNames.stream()
                .distinct()
                .limit(5)
                .toList();

        if (distinct.isEmpty()) {
            log.debug("Square Item Search Result is Empty");
            return new SquareItemSearchResult(
                    List.of(),
                    "SUCCESS",
                    "No items match the search query."
            );
        } else {
            log.debug("Square Item Search Result: {}", distinct.stream().collect(Collectors.joining(",")));
            return new SquareItemSearchResult(
                    distinct,
                    "SUCCESS",
                    "Found " + distinct.size() + " matching items (up to 5 shown)."
            );
        }
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Same semantics as old isEnabled(): only expose when Square is enabled
        return isSquareEnabled();
    }

    /**
     * Request payload the model must provide for this tool.
     */
    public static class SquareItemSearchRequest {

        @JsonPropertyDescription("The search text to search for items for sale in English language.")
        @JsonProperty(value = "search_text", required = true)
        public String searchText;
    }

    /**
     * Result returned to the model.
     */
    public record SquareItemSearchResult(
            @JsonProperty("items")
            List<String> items,
            @JsonProperty("status")
            String status,
            @JsonProperty("message")
            String message
            ) {

    }
}
