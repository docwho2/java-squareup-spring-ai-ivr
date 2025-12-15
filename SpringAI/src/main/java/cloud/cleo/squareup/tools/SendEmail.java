package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.service.FaceBookService;
import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.SquareCustomerService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.squareup.square.types.Customer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Send an email message to an employee, optionally enriching it with Square customer data based on the caller's phone
 * number.
 */
@Component
@RequiredArgsConstructor
public class SendEmail extends AbstractTool {

    private final SesClient sesClient;
    private final JsonMapper mapper;
    private final FaceBookService faceBookService;
    private final SquareCustomerService squareCustomerService;

    @Tool(
            name = SEND_EMAIL_FUNCTION_NAME,
            description = """
            Send an email message to an employee. Use this to relay information \
            from the caller to an employee. The assistant must provide a clear \
            subject and message body in English.
            """
    )
    public StatusMessageResult sendEmail(SendEmailRequestPayload r, ToolContext ctx) {

        // Centralized validation of required fields
        StatusMessageResult validationError = validateRequiredFields(r);
        if (validationError != null) {
            return validationError;
        }

        LexV2EventWrapper event = getEventWrapper(ctx);
        try {
            String customerEmail = null;
            Customer customer = null;

            // If we have a valid phone number and Square is enabled, try to look up the customer.
            if (event != null && event.hasValidUSE164Number() && squareCustomerService.isEnabled()) {
                var optCustomer = squareCustomerService.findCustomerByPhone(event.getPhoneE164());
                if (optCustomer.isPresent()) {
                    customer = optCustomer.get();
                    customerEmail = customer.getEmailAddress()
                            .filter(email -> !email.isBlank())
                            .orElse(null);
                }
            }

            // Build subject based on channel, like the original switch.
            final String subjectPrefix = switch (event.getChannelPlatform()) {
                case CHIME, CONNECT ->
                    "[From Voice " + event.getPhoneE164() + "] ";
                case TWILIO ->
                    "[From SMS " + event.getPhoneE164() + "] ";
                case FACEBOOK ->
                    "[From Facebook User " + faceBookService.getFacebookName(event.getSessionId()) + "] ";
                default ->
                    "[From " + event.getChannelPlatform() + "/" + event.getSessionId() + "] ";
            };

            final String subject = subjectPrefix + r.subject;

            // Start with the original message.
            String composedMessage = r.message;

            if (customer != null) {
                // Append Square customer record to email for reference, stripping cards.
                ObjectNode custJson = mapper.valueToTree(customer);
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

            return logAndReturnSuccess(
                    "The email has been successfully sent."
            );

        } catch (Exception e) {
            return logAndReturnError(
                    "An error has occurred, the email could not be sent.",
                    e
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
    
    @Override
    protected Class<?> requestPayloadType() {
        return SendEmailRequest.class;
    }
}
