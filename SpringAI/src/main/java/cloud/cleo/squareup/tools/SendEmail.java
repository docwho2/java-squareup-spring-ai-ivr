package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.FaceBookOperations;
import cloud.cleo.squareup.LexV2EventWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.squareup.square.types.Customer;
import com.squareup.square.types.CustomerFilter;
import com.squareup.square.types.CustomerQuery;
import com.squareup.square.types.CustomerTextFilter;
import com.squareup.square.types.SearchCustomersRequest;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

/**
 * Send an email message to an employee, optionally enriching it with
 * Square customer data based on the caller's phone number.
 */
@Component
@RequiredArgsConstructor
public class SendEmail extends AbstractTool {

    private final SesClient sesClient;
    private final ObjectMapper mapper;
    private final FaceBookOperations faceBookOperations;

    @Tool(
        name = "send_email_message",
        description = """
            Send an email message to an employee. Use this to relay information \
            from the caller to an employee. The assistant must provide a clear \
            subject and message body in English.
            """
    )
    public StatusMessageResult sendEmail(SendEmailRequestPayload r, ToolContext ctx) {

        LexV2EventWrapper event = getEventWrapper(ctx);

        try {
            String customerEmail = null;
            Customer customer = null;

            // If we have a valid phone number and Square is enabled, try to look up the customer.
            if (event != null && event.hasValidUSE164Number() && isSquareEnabled()) {
                try {
                    final var customerList = getSquareClient()
                            .customers()
                            .search(SearchCustomersRequest.builder()
                                    .query(CustomerQuery.builder()
                                            .filter(CustomerFilter.builder()
                                                    .phoneNumber(CustomerTextFilter.builder()
                                                            .exact(event.getPhoneE164())
                                                            .build())
                                                    .build())
                                            .build())
                                    .limit(1L)   // only need first match
                                    .build())
                            .get()
                            .getCustomers()
                            .orElse(null);

                    if (customerList != null && !customerList.isEmpty()) {
                        customer = customerList.get(0);
                        if (customer.getEmailAddress() != null && customer.getEmailAddress().isPresent()) {
                            customerEmail = customer.getEmailAddress().get();
                        }
                    }
                } catch (Exception e) {
                    // Don't fail the email if customer lookup breaks.
                    log.error("Error in Square customer lookup", e);
                }
            }

            // Build subject based on channel, like the original switch.
            final String subjectPrefix;
            if (event != null) {
                switch (event.getChannelPlatform()) {
                    case CHIME, CONNECT -> subjectPrefix =
                            "[From Voice " + event.getPhoneE164() + "] ";
                    case TWILIO -> subjectPrefix =
                            "[From SMS " + event.getPhoneE164() + "] ";
                    case FACEBOOK -> subjectPrefix =
                            "[From Facebook User " + faceBookOperations.getFacebookName(event.getSessionId()) + "] ";
                    default -> subjectPrefix =
                            "[From " + event.getChannelPlatform() + "/" + event.getSessionId() + "] ";
                }
            } else {
                // No context; fallback prefix.
                subjectPrefix = "[From Unknown Channel] ";
            }

            final String subject = subjectPrefix + r.subject;

            // Start with the original message.
            String composedMessage = r.message;

            if (customer != null) {
                // Append Square customer record to email for reference, stripping cards.
                final var myMapper = mapper.copy()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
                ObjectNode custJson = myMapper.valueToTree(customer);
                custJson.remove("cards");

                composedMessage = composedMessage
                        .concat("\n\n--\n\nSquare Customer Record:\n\n")
                        .concat(custJson.toPrettyString());
            }

            // must be final for lambda builder
            final var finalMessage = composedMessage;
            
            // Build SES email request.
            final var requestBuilder = SendEmailRequest.builder()
                    .destination(dest -> dest.toAddresses(r.employeeEmail))
                    .message(mesg -> mesg
                            .body(body -> body.text(cont -> cont.data(finalMessage)))
                            .subject(cont -> cont.data(subject)))
                    .source("CopperBot@CopperFoxGifts.com");

            // If we know the customer's email, set it as reply-to.
            if (customerEmail != null) {
                requestBuilder.replyToAddresses(customerEmail);
            }

            final var response = sesClient.sendEmail(requestBuilder.build());

            log.info("Sent email to {} with SES id {}", r.employeeEmail, response.messageId());
            log.info("Subject: {}", subject);
            log.info("Message: {}", composedMessage);

            return new StatusMessageResult(
                    "SUCCESS",
                    "The email has been successfully sent."
            );

        } catch (CompletionException e) {
            log.error("Unhandled error sending email (wrapped)", e.getCause());
            return new StatusMessageResult(
                    "FAILED",
                    "An error has occurred, the email could not be sent."
            );
        } catch (Exception e) {
            log.error("Unhandled error sending email", e);
            return new StatusMessageResult(
                    "FAILED",
                    "An error has occurred, the email could not be sent."
            );
        }
    }

    /**
     * Valid for all channels.
     * 
     * @param event
     * @return 
     */
    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return true;
    }

    /**
     * Request payload the model must provide for this tool.
     */
    public static class SendEmailRequestPayload {

        @JsonPropertyDescription("The employee email address that should receive the message.")
        @JsonProperty(value = "employee_email", required = true)
        public String employeeEmail;

        @JsonPropertyDescription("Subject for the email message in English language.")
        @JsonProperty(value = "subject", required = true)
        public String subject;

        @JsonPropertyDescription("The message body to relay to the employee in English language.")
        @JsonProperty(value = "message", required = true)
        public String message;
    }
}
