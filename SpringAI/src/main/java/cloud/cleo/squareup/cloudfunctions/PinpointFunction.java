package cloud.cleo.squareup.cloudfunctions;

import cloud.cleo.squareup.ClearChatMemory;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.stereotype.Component;

/**
 *
 * @author sjensen
 */
@Component
@RequiredArgsConstructor
public class PinpointFunction implements Function<SNSEvent, Void> {

    // Initialize the Log4j logger
    public static final Logger log = LogManager.getLogger(PinpointFunction.class);


    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final ClearChatMemory clearChatMemory;

    public final static ObjectMapper mapper = new ObjectMapper();

    record FileLink(String url, String mimeType, String name) {

    }

    @Override
    public Void apply(SNSEvent input) {
        // Only 1 record is every presented
        SNSEvent.SNS snsEvent = input.getRecords().get(0).getSNS();
        log.debug("Recieved SNS Event" + snsEvent);

        // Convert payload to Pinpoint Event
        PinpointEvent ppe;
        try {
            ppe = mapper.readValue(snsEvent.getMessage(), PinpointEvent.class);
        } catch( JsonProcessingException jpe ) {
            log.error("Cannot convert Pintpoint JSON to Object, processing aborted and returning null",jpe);
        }
        return null;
    }

    


 


   /**
     * The SNS payload we will receive from Incoming SMS messages.
     *
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PinpointEvent {

        /**
         * The phone number that sent the incoming message to you (in other words, your customer's phone number).
         */
        @JsonProperty(value = "originationNumber")
        private String originationNumber;

        /**
         * The phone number that the customer sent the message to (your dedicated phone number).
         */
        @JsonProperty(value = "destinationNumber")
        private String destinationNumber;

        /**
         * The registered keyword that's associated with your dedicated phone number.
         */
        @JsonProperty(value = "messageKeyword")
        private String messageKeyword;

        /**
         * The message that the customer sent to you.
         */
        @JsonProperty(value = "messageBody")
        private String messageBody;

        /**
         * The unique identifier for the incoming message.
         */
        @JsonProperty(value = "inboundMessageId")
        private String inboundMessageId;

        /**
         * The unique identifier of the message that the customer is responding to.
         */
        @JsonProperty(value = "previousPublishedMessageId")
        private String previousPublishedMessageId;
    }

}
