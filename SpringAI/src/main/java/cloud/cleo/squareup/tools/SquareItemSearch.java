package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.SquareItemService;
import cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.SUCCESS;
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
            Search for store product items by text and return up to 5 item names. 
            The result may not include all matching items if more than 5 exist. If you
            are conversing in another language other than English then you must translate 
            the searchText parameter into English.
            """
    )
    public SquareItemSearchResult searchItems(
            @ToolParam(description = "The search text to search for items for sale translated to English language.", required = true) String searchText
    ) {

        if (searchText == null || searchText.isBlank()) {
            return new SquareItemSearchResult(
                    List.of(),
                    StatusMessageResult.Status.FAILED,
                    "searchText is required for this operation"
            );
        }

        log.debug("Square Item Search for {}", searchText);

        // Use AbstractTool helper to generate combinations
        List<String> tokens = allCombinations(searchText);

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
    
    /**
     * Result returned to the model.
     */
    public record SquareItemSearchResult(
            List<String> items,
            Status status,
            String message
            ) {

    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Only expose when Square is actually enabled
        return squareItemService.isEnabled();
    }

    
}
