package cloud.cleo.squareup.service;

import com.squareup.square.SquareClient;
import com.squareup.square.types.SearchCatalogItemsRequest;
import com.squareup.square.types.SearchCatalogItemsResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class SquareItemService {

    private final @Nullable SquareClient squareClient;

    private final ExecutorService virtualThreadExecutor;

    public boolean isEnabled() {
        return squareClient != null;
    }

    /**
     * Search item names based on a list of token combinations.
     * Returns a de-duplicated list of names, limited to 5.
     * @param tokens
     * @return 
     */
    public List<String> searchItemNames(List<String> tokens) {
        if (!isEnabled()) {
            log.debug("Square is not enabled; skipping item search.");
            return Collections.emptyList();
        }

        if (tokens == null || tokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> itemNames = new ArrayList<>();

        log.debug("Launching {} item searches in parallel using virtual threads", tokens.size());

        try {
            List<Future<SearchCatalogItemsResponse>> futures = tokens.stream()
                    .map(token -> virtualThreadExecutor.submit(() -> {
                        log.debug("Executing search for [{}]", token);
                        return squareClient
                                .catalog()
                                .searchItems(SearchCatalogItemsRequest.builder()
                                        .textFilter(token)
                                        .limit(5)
                                        .build());
                    }))
                    .toList();

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
            return Collections.emptyList();
        }

        // De-duplicate and limit to 5 overall
        return itemNames.stream()
                .distinct()
                .limit(5)
                .toList();
    }
}