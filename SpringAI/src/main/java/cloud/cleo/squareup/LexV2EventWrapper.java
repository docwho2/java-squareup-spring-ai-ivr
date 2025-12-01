package cloud.cleo.squareup;

import cloud.cleo.squareup.service.FaceBookService;
import cloud.cleo.squareup.LexV2Event.Bot;
import cloud.cleo.squareup.cloudfunctions.PinpointFunction.PinpointEvent;
import cloud.cleo.squareup.config.SquareConfig.SquareProperties;
import cloud.cleo.squareup.enums.*;
import static cloud.cleo.squareup.enums.ChannelPlatform.*;
import static cloud.cleo.squareup.enums.LexInputMode.DTMF;
import static cloud.cleo.squareup.enums.LexInputMode.SPEECH;
import static cloud.cleo.squareup.enums.LexInputMode.TEXT;
import cloud.cleo.squareup.lang.LangUtil;
import cloud.cleo.squareup.lang.LangUtil.LanguageIds;
import static cloud.cleo.squareup.tools.AbstractTool.DRIVING_DIRECTIONS_VOICE_FUNCTION_NAME;
import static cloud.cleo.squareup.tools.AbstractTool.HANGUP_FUNCTION_NAME;
import static cloud.cleo.squareup.tools.AbstractTool.PRIVATE_SHOPPING_TEXT_FUNCTION_NAME;
import static cloud.cleo.squareup.tools.AbstractTool.PRIVATE_SHOPPING_VOICE_FUNCTION_NAME;
import static cloud.cleo.squareup.tools.AbstractTool.SWITCH_LANGUAGE_FUNCTION_NAME;
import static cloud.cleo.squareup.tools.AbstractTool.TRANSFER_FUNCTION_NAME;
import static cloud.cleo.squareup.tools.AbstractTool.WEBSITE_URL;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.pinpoint.model.NumberValidateResponse;

/**
 * Wrapper for Lex Input Event to add utility functions.
 *
 * @author sjensen
 */
public class LexV2EventWrapper {

    // Initialize the Log4j logger.
    private static final Logger log = LogManager.getLogger(LexV2EventWrapper.class);

    private static final PinpointClient pinpointClient = SpringContext.getBean(PinpointClient.class);

    private static final ObjectMapper mapper = SpringContext.getBean(ObjectMapper.class);

    private static final FaceBookService faceBookOperations = SpringContext.getBean(FaceBookService.class);

    private static final SquareProperties squareProperties = SpringContext.getBean(SquareProperties.class);

    @Getter
    private int blankCounter = 0;

    // 👇 New: track whether this is a new Lex session
    @Getter
    private final boolean newSession;

    /**
     * The underlying Lex V2 Event.
     */
    @Getter(AccessLevel.PUBLIC)
    private final LexV2Event event;

    /**
     * Language Support.
     */
    @Getter(AccessLevel.PUBLIC)
    private final LangUtil lang;

    /**
     * Wrap a Normal LexV2Event.
     *
     * @param event
     */
    public LexV2EventWrapper(LexV2Event event) {
        this.event = event;
        this.lang = new LangUtil(event.getBot().getLocaleId());

        // Always clear out action on incoming events if present
        final var attrs = getSessionAttributes();
        attrs.remove("action");

        boolean fresh = !attrs.containsKey("ai_initialized");
        if (fresh) {
            attrs.put("ai_initialized", "true");
        }
        this.newSession = fresh;

        if (getInputTranscript().isBlank()) {
            log.debug("Got blank input, so just silent or nothing");
            blankCounter = Integer.parseInt(attrs.getOrDefault("blankCounter", "0")) + 1;
            attrs.put("blankCounter", String.valueOf(blankCounter));
        } else {
            // Always clear from session if it exists when there is no blank input
            attrs.remove("blankCounter");
        }
    }

    /**
     * Turn a Pinpoint Event into a Wrapped LexEvent.
     *
     * @param ppe
     */
    public LexV2EventWrapper(PinpointEvent ppe) {
        this(LexV2Event.builder()
                .withInputMode(LexInputMode.TEXT.getMode())
                // Exclude + from the E164 to be consistant with Twilio (shouldn't use + in sessionID)
                .withSessionId(ppe.getOriginationNumber().substring(1))
                // Mimic Platform input type of Pinpoint
                .withRequestAttributes(Map.of("x-amz-lex:channels:platform", ChannelPlatform.PINPOINT.getChannel()))
                // The incoming SMS body will be in the input Transcript
                .withInputTranscript(ppe.getMessageBody())
                // SMS has no locale target, just use en_US
                .withBot(Bot.builder().withLocaleId("en_US").build())
                // Need Blank Session attributes
                .withSessionState(LexV2Event.SessionState.builder().withSessionAttributes(new HashMap<>()).build())
                .build());
    }

    /**
     * The Java Locale for this Bot request.
     *
     * @return
     */
    public Locale getLocale() {
        return lang.getLocale();
    }

    /**
     * Get a Language Specific String.
     *
     * @param id
     * @return
     */
    public String getLangString(LanguageIds id) {
        return lang.getString(id);
    }

    /**
     * Return Input Mode as Enumeration.
     *
     * @return InputMode enumeration
     */
    public LexInputMode getInputMode() {
        return LexInputMode.fromString(event.getInputMode());
    }

    /**
     * Is the event based on speech input (or DTMF which is still voice).
     *
     * @return
     */
    public boolean isVoice() {
        return switch (getInputMode()) {
            case SPEECH, DTMF ->
                true;
            default ->
                false;
        };
    }

    /**
     * Is the event based on text input.
     *
     * @return
     */
    public boolean isText() {
        return switch (getInputMode()) {
            case TEXT ->
                true;
            default ->
                false;
        };
    }

    /**
     * The Channel this event came from. Chime, Twilio, Facebook, etc..
     *
     * @return
     */
    public ChannelPlatform getChannelPlatform() {
        if (event.getRequestAttributes() != null && event.getRequestAttributes().containsKey("x-amz-lex:channels:platform")) {
            return ChannelPlatform.fromString(event.getRequestAttributes().get("x-amz-lex:channels:platform"));
        }
        // Unknown will be something new we haven't accounted for or direct Lex API calls (console, aws cli, etc..)
        return ChannelPlatform.UNKNOWN;
    }

    /**
     * Get the calling (or SMS originating) number for the session. For Channels like Facebook or CLI testing, this will
     * not be available and null.
     *
     * @return E164 number or null if not applicable to channel.
     */
    public String getPhoneE164() {
        return switch (getChannelPlatform()) {
            case CHIME ->
                // For Chime we will pass in the calling number as Session Attribute callingNumber
                event.getSessionState().getSessionAttributes() != null
                ? event.getSessionState().getSessionAttributes().get("callingNumber") : null;
            case TWILIO, PINPOINT ->
                // Twilio channel will use sessiond ID, however without +, so prepend to make it full E164
                "+".concat(event.getSessionId());
            default ->
                null;
        };
    }

    private static final Pattern US_E164_PATTERN = Pattern.compile("^\\+1[2-9]\\d{2}[2-9]\\d{6}$");

    /**
     * Is the callers number a valid US Phone number
     *
     * @return
     */
    public final boolean hasValidUSE164Number() {
        final var callingNumber = getPhoneE164();
        if (callingNumber == null || callingNumber.isBlank()) {
            return false;
        }
        return US_E164_PATTERN.matcher(callingNumber).matches();
    }

    /**
     * Store this in case we try and send SMS twice ever, don't want to pay for the lookup again since it costs money.
     * AWS usually calls the same Lambda, but anyways no harm to try and cache to save a couple cents here and there.
     */
    private static final Map<String, NumberValidateResponse> validatePhoneMap = new HashMap<>();

    public final boolean hasValidUSMobileNumber() {
        if (!hasValidUSE164Number()) {
            return false;
        }
        try {
            final var callingNumber = getPhoneE164();
            NumberValidateResponse numberValidateResponse;
            log.debug("Validating " + callingNumber + "  with Pinpoint");
            if (!validatePhoneMap.containsKey(callingNumber)) {
                // First lookup, call pinpoint
                numberValidateResponse = pinpointClient
                        .phoneNumberValidate(t -> t.numberValidateRequest(r -> r.isoCountryCode("US").phoneNumber(callingNumber)))
                        .numberValidateResponse();
                log.debug("Pinpoint returned " + convertPinpointResposeToJson(numberValidateResponse));
                // Add to cache
                validatePhoneMap.put(callingNumber, numberValidateResponse);
            } else {
                numberValidateResponse = validatePhoneMap.get(callingNumber);
                log.debug("Using cached Pinpoint response " + convertPinpointResposeToJson(numberValidateResponse));
            }
            // The description of the phone type. Valid values are: MOBILE, LANDLINE, VOIP, INVALID, PREPAID, and OTHER.
            return switch (numberValidateResponse.phoneType()) {
                case "MOBILE", "PREPAID" ->
                    true;
                default ->
                    false;
            };
        } catch (CompletionException e) {
            log.error("Unhandled Error", e.getCause());
            return false;
        } catch (Exception e) {
            log.error("Error making pinpoint call", e);
            return false;
        }
    }

    private static String convertPinpointResposeToJson(NumberValidateResponse res) {
        return mapper.valueToTree(mapper.convertValue(res.toBuilder(), NumberValidateResponse.serializableBuilderClass())).toPrettyString();
    }

    /**
     * The textual input to process. No input should changed to "blank" so the model will know caller said nothing.
     *
     * @return
     */
    public String getInputTranscript() {
        final var it = event.getInputTranscript();
        return it.isBlank() ? "blank" : it;
    }

    /**
     * The Intent for this request.
     *
     * @return
     */
    public String getIntent() {
        return event.getSessionState().getIntent().getName();
    }

    /**
     * Lex Session Attributes.
     *
     * @return
     */
    public Map<String, String> getSessionAttributes() {
        var ss = event.getSessionState();
        if (ss.getSessionAttributes() == null) {
            ss.setSessionAttributes(new HashMap<>());
        }
        return ss.getSessionAttributes();
    }

    public String getSessionAttribute(String key) {
        return getSessionAttributes().get(key);
    }

    public String putSessionAttribute(String key, String val) {
        return getSessionAttributes().put(key, val);
    }

    public String putSessionAttributeAction(String val) {
        return getSessionAttributes().put("action", val);
    }

    public String putSessionAttributeBotResponse(String val) {
        return getSessionAttributes().put("bot_response", val);
    }

    public boolean hasSessionAttribute(String key) {
        return getSessionAttributes().containsKey(key);
    }

    public boolean hasSessionAttributeAction() {
        return getSessionAttributes().containsKey("action");
    }

    /**
     * Session Id for the interaction (raw from the Lex Request).
     *
     * @return
     */
    public String getSessionId() {
        return event.getSessionId();
    }

    /**
     * Session Id used for Chat Memory. Since some channels like Pinpoint and Twilio send in phone number, append date
     * to those that use static values. Chime will have unique sessionId per call for example so no need to change that.
     *
     * @return
     */
    public String getChatMemorySessionId() {
        return switch (getChannelPlatform()) {
            case FACEBOOK, TWILIO, PINPOINT ->
                getSessionId() + LocalDate.now(ZoneId.of("America/Chicago"));
            default ->
                getSessionId();
        };
    }

    /**
     * Is this request from the Facebook Channel.
     *
     * @return
     */
    public boolean isFacebook() {
        return getChannelPlatform().equals(FACEBOOK);
    }

    /**
     * Generate a System customized for channel/input type.
     *
     * @return
     */
    public String getSystemPrompt() {
        final var sb = new StringBuilder();

        // General Prompting
        sb.append("""
                  Please be a helpful assistant named "Copper Bot" for a retail store named "Copper Fox Gifts", 
                  which has clothing items, home decor, gifts of all kinds, speciality foods, and much more.  
                  The store is located at 160 Main Street, Wahkon Minnesota, near Lake Mille Lacs.
                  The store opened in October of 2021 and moved to its larger location in May of 2023.
                  Outside normal business hours, we offer a "Private Shopping Experience" where a staff member will open 
                  the store outside normal hours, and this can be scheduled on our website from one of the top level menu "Private Shopping".
                  We have a one hour lead time on appointments so if we're closed, they could be shopping privately within one hour! 
                  Do mention how great it would be to have the store all to themselves and how we try to accommodate all requests.  
                  """);

        // Main Website adn FB
        sb.append("The Web Site for Copper Fox Gifts is ").append(WEBSITE_URL).append(" and we frequently post our events and information on sales ")
                .append(" on our Facebook Page which is also linked at top level menu on our website.  ");

        // Local Stuff to recommend
        sb.append("""
                  Muggs of Mille Lacs is a great restaurant next door that serves some on the best burgers 
                  in the lake area and has a large selection draft beers and great pub fare. 
                  Tulibee Tavern is another great restaurant across the street that serves more home cooked type meals at reasonable prices.  
                  """);

        // We want to receieve all emails in English so we can understand them :-)
        sb.append("When executing send_email_message function, translate the subject and message request parameteres to English.  ");

        // Square must be enabled for all of the below, so exclude when deploying without Sqaure enabled
        if (squareProperties.enabled()) {
            // Privacy
            sb.append("Do not give out employee phone numbers, only email addresses.  You can give out the main store phone number which is ")
                    .append(System.getenv("MAIN_NUMBER")).append(".  ");
            sb.append("Do not give out the employee list.  You may confirm the existance of an employee and give the full name and email.  ");

            // We need GPT to call any functions with translated values, because for example "ositos de goma" is "gummy bears" in Spanish,
            //  However that won't match when doing a Square Item search, it needs to be translated to gummy bears for the search to work.
            // General statement didn't work well, but calling the below out works great
            sb.append("When executing store_product_item function, translate the search_text to English.  ");

            // Because we search on all terms, tell GPT to look at results and analyze whether the exact search term matched, or maybe a sub-string matched
            sb.append("When executing store_product_item function the results may include items that don't match exactly, ")
                    .append("so check to see if the full search_text is contained in the result to indicate an exact match, otherwise indicate to user ")
                    .append("that those results may be similar items to what they asked about.  ");
        }

        // Mode specific prompting
        switch (getInputMode()) {
            case TEXT -> {
                switch (getChannelPlatform()) {
                    case FACEBOOK -> {
                        // Don't need very short or char limit, but we don't want to output a book either
                        sb.append("The user is interacting via Facebook Messenger.  Use emoji in responses when appropiate.  ");

                        // Personalize with Name
                        // 👇 Try to get from Lex session cache first
                        String name = getSessionAttribute("fb_user_name");

                        if (name == null) {
                            // Not cached yet, call Facebook API once
                            name = faceBookOperations.getFacebookName(getSessionId());
                            // Cache it even if it's "Unknown" to avoid repeated calls
                            putSessionAttribute("fb_user_name", name);
                        }

                        if (!"Unknown".equalsIgnoreCase(name)) {
                            sb.append("The user's name is ").append(name)
                                    .append(".  Please greet the user by name and personalize responses when appropiate.  ");
                        }
                    }
                    case TWILIO, PINPOINT -> {
                        // Try and keep SMS segements down, hence the "very" short reference and character preference
                        sb.append("The user is interacting via SMS.  Please keep answers very short and concise, preferably under 180 characters.  ");

                        // We can't move conversation to person like Facebook, so tell them to call
                        sb.append("If the user wants to speak or deal with a person in general or leave a voicemail, instruct them to call ")
                                .append(System.getenv("MAIN_NUMBER")).append(" which rings the main phone in the store.  ");
                    }
                    default -> {
                        // Keep very short for anything else (CLI and lex Console testing)
                        sb.append("Please keep answers very short and concise.  ");
                    }
                }
                sb.append("Please call the ").append(PRIVATE_SHOPPING_TEXT_FUNCTION_NAME)
                        .append("""
                         function to get the direct booking URL when the person is interested in the private shopping experience.  This is 
                         really one of the more innovative services we provide and we want to ensure its as easy as possible for customers
                         to book their appointments.  
                        """);

                // Since we are fallback intent, from a Text input perspective, we can support any language the model understands
                sb.append("Detect the language only on the initial prompt and respond in that language for the whole conversation, only change language after that if the user requests it.  ");
            }
            case SPEECH, DTMF -> {
                sb.append("The user is interacting with speech via a telephone call.  please keep answers short and concise.  ");

                // Blank input, meaning silienece timeout which is a speech only thing
                sb.append("When the prompt is exactly 'blank', this means the caller did not say anything, so try and engage in conversation and also suggest ")
                        .append("queries the caller might be interested in (Hours, Private Shopping, Location, Product Search, Language Change, etc.).  ");

                // Hangup
                sb.append("When the caller indicates they are done with the conversation, execute the ").append(HANGUP_FUNCTION_NAME).append(" function.  ");

                // Offer up Driving directions for callers
                sb.append("When asking about location, you can send the caller a directions link if they are interested, execute the ").append(DRIVING_DIRECTIONS_VOICE_FUNCTION_NAME).append(" function.  ");

                // Always answer with a question to illicit the next repsonse, this makes the voice interaction more natural
                sb.append("When responding always end the response with a question to illicit the next input since we are interacting via telephone.  ");

                // Speech Languages and switching between them at any time
                sb.append("If the caller wants to interact in ")
                        .append(Arrays.stream(Language.values()).map(Language::toString).collect(Collectors.joining(" or ")))
                        .append(" execute the ").append(SWITCH_LANGUAGE_FUNCTION_NAME)
                        .append(" function and then respond to all future prompts in that language.  ");

                // Transferring
                if (squareProperties.enabled()) {
                    sb.append("To transfer or speak with an employee that has a phone number, execute the ").append(TRANSFER_FUNCTION_NAME).append(" function.  ");
                    sb.append("Do not provide callers employee phone numbers, you can only use the phone numbers to execute the ").append(TRANSFER_FUNCTION_NAME).append(" function.  ");
                }
                sb.append("If the caller wants to just speak to any person in general or leave a voicemail, execute ")
                        .append(TRANSFER_FUNCTION_NAME).append(" with ").append(System.getenv("MAIN_NUMBER"))
                        .append(" which rings the main phone in the store.  ");

                // Toll fraud protect
                sb.append("Do not allow calling ").append(TRANSFER_FUNCTION_NAME).append(" function with arbitrary phone numbers provided by the user.  ");

                sb.append("Please call the ").append(PRIVATE_SHOPPING_VOICE_FUNCTION_NAME)
                        .append("""
                         function to get the direct booking URL when the person is interested in the private shopping experience.  This is 
                         really one of the more innovative services we provide and we want to ensure its as easy as possible for customers
                         to book their appointments. The function will tell if you the message was sent to their device or unable to send.
                        """);
            }
        }

        return sb.toString();
    }

}
