package cloud.cleo.wahkon.service;

import cloud.cleo.wahkon.config.FacebookProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Log4j2
public class FacebookIngestService {

    private static final String FIELDS = "id,message,created_time,permalink_url";

    @Autowired
    @Qualifier("facebookRestClient")
    private RestClient fb;

    @Autowired
    private JsonMapper objectMapper;

    public List<FbPost> fetchRecentPosts(FacebookProperties.Page page, int maxPosts) {
        var results = new ArrayList<FbPost>(maxPosts);
        log.debug("Incoming Page request {}", page);

        String nextUrl = "/%s/posts".formatted(page.pageId());

        while (nextUrl != null && results.size() < maxPosts) {

            var jsonOpt = getJson(nextUrl, maxPosts);
            if (jsonOpt.isEmpty()) {
                break;
            }

            var root = jsonOpt.get();
            var data = root.path("data");

            if (data.isArray()) {
                for (var n : data) {
                    if (results.size() >= maxPosts) {
                        break;
                    }
                    parsePost(n).ifPresent(results::add);
                }
            }

            var pagingNext = root.path("paging").path("next");
            nextUrl = pagingNext.isTextual() ? pagingNext.asText() : null;
        }

        log.debug("Results has {} entries", results.size());
        return results;
    }

    private Optional<JsonNode> getJson(String urlOrPath, int limit) {
        try {
            final String json;

            if (urlOrPath != null && urlOrPath.startsWith("http")) {
                // paging.next already includes limit/fields/after — call it directly
                log.debug("FB GET full-url = {}", urlOrPath);

                json = fb.get()
                        .uri(urlOrPath)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class);
            } else {
                log.debug("FB GET path = {}", urlOrPath);

                json = fb.get()
                        .uri(uriBuilder -> {
                            var uri = uriBuilder
                                    .path(urlOrPath)
                                    .queryParam("limit", limit)
                                    .queryParam("fields", FIELDS)
                                    .build();
                            log.debug("FB full URI = {}", uri);
                            return uri;
                        })
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class);
            }

            if (json == null || json.isBlank()) {
                log.debug("FB response EMPTY for {}", urlOrPath);
                return Optional.empty();
            }

            log.debug("FB raw response = {}", json);
            return Optional.of(objectMapper.readTree(json));

        } catch (Exception e) {
            log.warn("Facebook API call failed: {}", urlOrPath, e);
            return Optional.empty();
        }
    }

    private Optional<FbPost> parsePost(JsonNode node) {
        var id = node.path("id").asText(null);
        if (id == null) {
            return Optional.empty();
        }

        var message = node.path("message").asText("");
        var createdTime = node.path("created_time").asText(null);
        var permalink = node.path("permalink_url").asText(null);

        return Optional.of(new FbPost(id, message, createdTime, permalink));
    }

    public record FbPost(String id, String message, String createdTime, String permalinkUrl) {

    }
}
