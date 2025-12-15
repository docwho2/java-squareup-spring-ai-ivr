package cloud.cleo.squareup.cloudfunctions;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.LexV2Response;
import static cloud.cleo.squareup.lang.LangUtil.LanguageIds.UNHANDLED_EXCEPTION;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;

import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 *
 * @author sjensen
 */
@Component
@RequiredArgsConstructor
@Log4j2
public class PinpointFunction implements Function<SNSEvent, Void> {

    private final LexFunction lexFunction;
    private final JsonMapper mapper;
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
        } catch (JacksonException jpe) {
            log.error("Cannot convert Pintpoint JSON to Object, processing aborted and returning null", jpe);
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
        final var result = snsClient.publish(b -> b.phoneNumber(ppe.originationNumber())
                .message(finalResponse));
        log.info("SMS Bot Response sent to " + ppe.originationNumber() + " with SNS id of " + result.messageId());

        return null;
    }

    /**
     * The SNS payload we receive from incoming SMS messages.
     */
    public record PinpointEvent(
            String originationNumber,
            String destinationNumber,
            String messageKeyword,
            String messageBody,
            String inboundMessageId,
            String previousPublishedMessageId
            ) {

    }

}
