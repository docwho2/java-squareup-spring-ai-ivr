package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.SquareItemService;
import cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.SUCCESS;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Search for items based on search query using virtual threads.
 */
@Component
@RequiredArgsConstructor
public class SquareItemSearch extends AbstractTool {

    private final SquareItemService squareItemService;

    @Tool(
            name = "store_product_item",
            description = """
            Search for store product items by text and return up to 5 item names. \
            The result may not include all matching items if more than 5 exist.
            """
    )
    public SquareItemSearchResult searchItems(SquareItemSearchRequest r) {

        // Centralized validation of required fields
        StatusMessageResult validationError = validateRequiredFields(r);
        if (validationError != null) {
            return new SquareItemSearchResult(
                    List.of(),
                    validationError.status(),
                    validationError.message()
            );
        }

        log.debug("Square Item Search for {}", r.searchText);

        // Use AbstractTool helper to generate combinations
        List<String> tokens = allCombinations(r.searchText);

        List<String> distinct = squareItemService.searchItemNames(tokens);

        if (distinct.isEmpty()) {
            log.debug("Square Item Search Result is Empty");
            return new SquareItemSearchResult(
                    List.of(),
                    SUCCESS,
                    "No items match the search query."
            );
        } else {
            log.debug("Square Item Search Result: {}",
                    distinct.stream().collect(java.util.stream.Collectors.joining(",")));
            return new SquareItemSearchResult(
                    distinct,
                    SUCCESS,
                    "Found " + distinct.size() + " matching items (up to 5 shown)."
            );
        }
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Only expose when Square is actually enabled
        return squareItemService.isEnabled();
    }

    /**
     * Request payload the model must provide for this tool.
     */
    public static class SquareItemSearchRequest {

        @JsonPropertyDescription("The search text to search for items for sale in English language.")
        @JsonProperty(value = "search_text", required = true)
        public String searchText;
    }
    
    @Override
    protected Class<?> requestPayloadType() {
        return SquareItemSearchRequest.class;
    }

    /**
     * Result returned to the model.
     */
    public record SquareItemSearchResult(
            @JsonProperty("items")
            List<String> items,
            @JsonProperty("status")
            Status status,
            @JsonProperty("message")
            @ToolParam(description = "City or state name", required = true)
            String message
            ) {

    }
}
