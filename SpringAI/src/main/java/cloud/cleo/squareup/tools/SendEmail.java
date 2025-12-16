package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.service.FaceBookService;
import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.SquareCustomerService;
import com.squareup.square.types.Customer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
    public StatusMessageResult sendEmail(
            @ToolParam(description = "The employee email address that should receive the message.", required = true) String employeeEmail,
            @ToolParam(description = "Subject for the email message in English language.", required = true) String subject,
            @ToolParam(description = "The message body to relay to the employee in English language.", required = true) String message,
            ToolContext ctx) {

        final var incomingParams = List.of(employeeEmail, subject, message);
        for (final var param : incomingParams) {
            if (param == null || param.isBlank()) {
                return logAndReturnError(
                        "All parameters are required and cannot be blank"
                );
            }
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

            final String emailSubject = subjectPrefix + subject;

            // Start with the original message.
            String composedMessage = message;

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
                    .destination(dest -> dest.toAddresses(employeeEmail))
                    .message(mesg -> mesg
                    .body(body -> body.text(cont -> cont.data(finalMessage)))
                    .subject(cont -> cont.data(emailSubject)))
                    .source("CopperBot@CopperFoxGifts.com");

            // If we know the customer's email, set it as reply-to.
            if (customerEmail != null) {
                requestBuilder.replyToAddresses(customerEmail);
            }

            final var response = sesClient.sendEmail(requestBuilder.build());

            log.info("Sent email to {} with SES id {}", employeeEmail, response.messageId());
            log.info("Subject: {}", emailSubject);
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

}
