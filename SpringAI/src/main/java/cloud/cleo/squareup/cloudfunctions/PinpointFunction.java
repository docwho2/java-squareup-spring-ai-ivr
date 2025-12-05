package cloud.cleo.squareup.cloudfunctions;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.LexV2Response;
import static cloud.cleo.squareup.lang.LangUtil.LanguageIds.UNHANDLED_EXCEPTION;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 *
 * @author sjensen
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class PinpointFunction implements Function<SNSEvent, Void> {

    private final LexFunction lexFunction;
    private final ObjectMapper mapper;
    private final SnsClient snsClient;

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
            return null;
        }
        
        final LexV2EventWrapper wrapper = new LexV2EventWrapper(ppe);
        String botResponse;
        try {
            // Wrapped Event Class
            
            LexV2Response response = lexFunction.apply(wrapper.getEvent());

            // Take repsonse body message from the LexV2Reponse and respond to SMS via SNS
            botResponse = response.getMessages().getFirst().getContent();
        } catch (Exception ex) {
             log.error("Unhandled Exception", ex);
            // Unhandled Exception
            botResponse = wrapper.getLangString(UNHANDLED_EXCEPTION);
        }
        
        final var finalResponse = botResponse;
        final var result = snsClient.publish(b -> b.phoneNumber(ppe.getOriginationNumber())
                    .message(finalResponse));
            log.info("SMS Bot Response sent to " + ppe.getOriginationNumber() + " with SNS id of " + result.messageId());

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
         * The phone number that sent the incoming message to you (in other
         * words, your customer's phone number).
         */
        @JsonProperty(value = "originationNumber")
        private String originationNumber;

        /**
         * The phone number that the customer sent the message to (your
         * dedicated phone number).
         */
        @JsonProperty(value = "destinationNumber")
        private String destinationNumber;

        /**
         * The registered keyword that's associated with your dedicated phone
         * number.
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
         * The unique identifier of the message that the customer is responding
         * to.
         */
        @JsonProperty(value = "previousPublishedMessageId")
        private String previousPublishedMessageId;
    }

}
