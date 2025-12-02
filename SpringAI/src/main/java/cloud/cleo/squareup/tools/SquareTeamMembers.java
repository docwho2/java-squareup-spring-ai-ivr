package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.SquareTeamMemberService;
import cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.FAILED;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.SUCCESS;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.squareup.square.types.TeamMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Return employees (team members) from the Square API.
 */
@Component
@RequiredArgsConstructor
public class SquareTeamMembers extends AbstractTool {

    private final SquareTeamMemberService squareTeamMemberService;

    @Tool(
            name = "team_members",
            description = """
            Return the employee names, phone numbers, and email addresses for \
            this store location. The assistant MUST NOT reveal employee phone \
            numbers to callers or read the entire list aloud; phone numbers are \
            only for internal use (e.g., call transfers).
            """
    )
    public SquareTeamMembersResult getTeamMembers(ToolContext ctx) {

        if (!squareTeamMemberService.isEnabled()) {
            return new SquareTeamMembersResult(
                    List.of(),
                    FAILED,
                    "Square is not enabled; cannot retrieve team members."
            );
        }

        try {
            List<TeamMember> teamMembers = squareTeamMemberService.getActiveTeamMembers();

            if (teamMembers.isEmpty()) {
                return new SquareTeamMembersResult(
                        List.of(),
                        SUCCESS,
                        "No active team members were found for this location."
                );
            }

            List<Employee> employees = teamMembers.stream()
                    .map(Employee::new)
                    .toList();

            return new SquareTeamMembersResult(
                    employees,
                    SUCCESS,
                    "Returned active employees for this store location."
            );

        } catch (Exception ex) {
            final var er = logAndReturnError(ex);
            return new SquareTeamMembersResult(
                    List.of(),
                    er.status(),
                    er.message()
            );
        }
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        // Only expose this tool when Square is enabled
        return squareTeamMemberService.isEnabled();
    }

    /**
     * Single employee entry.
     */
    public static class Employee {

        @JsonPropertyDescription("Employee first name.")
        @JsonProperty("first_name")
        public String firstName;

        @JsonPropertyDescription("Employee last name.")
        @JsonProperty("last_name")
        public String lastName;

        @JsonPropertyDescription("""
            Employee phone number in E.164 format. \
            This is for internal use (e.g., call transfers) and MUST NOT be \
            revealed directly to the caller.
            """)
        @JsonProperty("phone_number")
        public String phoneNumber;

        @JsonPropertyDescription("Employee email address, to be used to send internal messages.")
        @JsonProperty("email")
        public String email;

        public Employee(TeamMember tm) {
            this.firstName = tm.getGivenName().orElse(null);
            this.lastName = tm.getFamilyName().orElse(null);
            this.phoneNumber = tm.getPhoneNumber().orElse(null);
            this.email = tm.getEmailAddress().orElse(null);
        }
    }

    /**
     * Result returned from the tool.
     */
    public record SquareTeamMembersResult(
            @JsonProperty("employees")
            List<Employee> employees,
            @JsonProperty("status")
            Status status,
            @JsonProperty("message")
            String message
            ) {

    }
}
