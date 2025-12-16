package cloud.cleo.squareup.service;

import static cloud.cleo.squareup.tools.AbstractTool.PRIVATE_SHOPPING_URL;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Perform various Facebook operations. Used when Channel is FB.
 * Uses Spring's RestClient for HTTP calls to the Facebook Graph API.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class FaceBookService {

    private final JsonMapper mapper;
    private final RestClient restClient = RestClient.builder()
                .baseUrl("https://graph.facebook.com")
                .build();
    
    private final static String FB_API_VERSION = "v24.0";

    // Inject from env/properties instead of System.getenv
    @Value("${fb.page-id:${FB_PAGE_ID:}}")
    private String pageId;

    @Value("${fb.page-access-token:${FB_PAGE_ACCESS_TOKEN:}}")
    private String pageAccessToken;

    public boolean isEnabled() {
        return pageId != null && !pageId.isBlank()
            && pageAccessToken != null && !pageAccessToken.isBlank();
    }
    
    
    public Optional<FbPost> fetchLatestPost() {
        if (!isEnabled()) {
            return Optional.empty();
        }

        try {
            // limit=1 => newest only (the one you want)
            var root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(FB_API_VERSION, pageId,"posts")
                            .queryParam("access_token", pageAccessToken)
                            .queryParam("limit", 1)
                            .queryParam("fields", "id,message,created_time,permalink_url")
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null) {
                return Optional.empty();
            }

            if (root.has("error")) {
                log.warn("Facebook Graph error: {}", root.toPrettyString());
                return Optional.empty();
            }

            var data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                return Optional.empty();
            }

            return parsePost(data.get(0));

        } catch (Exception e) {
            log.warn("Facebook latest post fetch failed", e);
            return Optional.empty();
        }
    }
    
    private Optional<FbPost> parsePost(JsonNode node) {

        var message = node.path("message").asText("");
        var postCreatedTime = node.path("created_time").asText(null);
        var permalink = node.path("permalink_url").asText(null);

        return Optional.of(new FbPost(message, postCreatedTime, permalink));
    }

    public record FbPost(String message, String postCreatedTime, String permalinkUrl) {}

    /**
     * Transfer control of the Messenger thread session from Bot control to the Inbox.
     *
     * https://developers.facebook.com/docs/messenger-platform/handover-protocol/conversation-control
     * @param recipientId
     */
    public void transferToInbox(String recipientId) {
        try {
            var json = mapper.createObjectNode();
            json.put("target_app_id", "263902037430900"); // Inbox app id
            json.putObject("recipient").put("id", recipientId);

            log.debug("Post payload for thread control: {}", json::toPrettyString);

            JsonNode result = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(FB_API_VERSION, pageId, "pass_thread_control")
                            .queryParam("access_token", pageAccessToken)
                            .build())
                    .body(json)
                    .retrieve()
                    .body(JsonNode.class);

            log.debug("FB Pass Thread Control result: {}", result::toPrettyString);

            boolean success = result != null
                    && result.findValue("success") != null
                    && result.findValue("success").asBoolean();

            if (success) {
                log.debug("Call succeeded in passing thread control");
            } else {
                log.debug("Call FAILED to pass thread control");
            }

        } catch (Exception e) {
            log.error("Facebook Pass Thread Control error", e);
        }
    }

    /**
     * Adds a static menu button so when bot calls for the URL, it persists as a menu item.
     * https://developers.facebook.com/docs/messenger-platform/send-messages/persistent-menu/
     * @param psid
     */
    public void addPrivateShoppingMenu(String psid) {
        try {
            var json = mapper.createObjectNode();

            json.put("psid", psid);

            json.putArray("persistent_menu")
                    .addObject()
                    .put("locale", "default")
                    .put("composer_input_disabled", false)
                    .putArray("call_to_actions")
                    .addObject()
                    .put("type", "web_url")
                    .put("url", "https://" + PRIVATE_SHOPPING_URL)
                    .put("title", "Book Shopping Appointment Now!")
                    .put("webview_height_ratio", "full");

            log.debug("Post payload for Private Shopping Menu: {}", json::toPrettyString);

            JsonNode result = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(FB_API_VERSION, "me", "custom_user_settings")
                            .queryParam("access_token", pageAccessToken)
                            .build())
                    .body(json)
                    .retrieve()
                    .body(JsonNode.class);

            log.debug("FB Private Shopping Menu send result: {}", result::toPrettyString);

            if (result != null && result.findValue("message_id") != null) {
                log.debug("Call succeeded in sending Private Shopping Menu");
            } else {
                log.debug("Call FAILED to send Private Shopping Menu");
            }

        } catch (Exception e) {
            log.error("Facebook Messenger Private Shopping Menu send failed", e);
        }
    }

    /**
     * Send our private Shopping URL as a Messenger Button.
     * Left for compatibility; bot sending raw URL is usually cleaner.
     * @param psid
     * @return 
     */
    @Deprecated
    public boolean sendPrivateBookingURL(String psid) {
        try {
            var json = mapper.createObjectNode();

            json.putObject("recipient").put("id", psid);

            json.putObject("message").putObject("attachment")
                    .put("type", "template").putObject("payload")
                    .put("template_type", "button")
                    .put("text", "Book Your Private Shopping Experience")
                    .putArray("buttons")
                    .addObject()
                    .put("type", "web_url")
                    .put("url", "https://" + PRIVATE_SHOPPING_URL)
                    .put("title", "Book Now!")
                    .put("webview_height_ratio", "full");

            log.debug("Post payload for URL push: {}", json::toPrettyString);

            JsonNode result = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(FB_API_VERSION, "me", "messages")
                            .queryParam("access_token", pageAccessToken)
                            .build())
                    .body(json)
                    .retrieve()
                    .body(JsonNode.class);

            log.debug("FB message URL send result: {}", result::toPrettyString);

            boolean success = result != null && result.findValue("message_id") != null;
            if (success) {
                log.debug("Call succeeded in sending URL in FB Messenger");
            } else {
                log.debug("Call FAILED to send URL in FB Messenger");
            }
            return success;

        } catch (Exception e) {
            log.error("Facebook Messenger send failed", e);
            return false;
        }
    }

    /**
     * Given a Facebook user Page Scoped ID, get the user's full name.
     * @param id
     * @return 
     */
    public String getFacebookName(String id) {
        try {
            JsonNode result = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(FB_API_VERSION, id)
                            .queryParam("access_token", pageAccessToken)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            log.debug("FB Graph Query result: {}", result::toPrettyString);

            if (result == null) {
                return "Unknown";
            }

            // Check for a single name field first
            if (result.hasNonNull("name")) {
                return result.get("name").asText();
            }

            // Or first + last
            if (result.hasNonNull("first_name") && result.hasNonNull("last_name")) {
                return result.get("first_name").asText() + " " + result.get("last_name").asText();
            }

        } catch (Exception e) {
            log.error("Facebook user name retrieval error", e);
        }

        return "Unknown";
    }
}
