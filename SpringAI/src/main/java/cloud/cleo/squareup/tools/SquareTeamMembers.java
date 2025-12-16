package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import cloud.cleo.squareup.service.SquareTeamMemberService;
import cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status;
import static cloud.cleo.squareup.tools.AbstractTool.StatusMessageResult.Status.SUCCESS;
import com.squareup.square.types.TeamMember;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
            Return the employee names, phone numbers, and email addresses for 
            this store location. The assistant MUST NOT reveal employee phone 
            numbers to callers or read the entire list aloud; phone numbers are 
            only for internal use (e.g., call transfers).  Email address may be used 
            to send emails to employees and are safe to give out.
            """
    )
    public SquareTeamMembersResult getTeamMembers() {

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
    public record Employee(
            String firstName,
            String lastName,
            String phoneNumber,
            String email
            ) {

        public Employee(TeamMember tm) {
            this(
                    tm.getGivenName().orElse(null),
                    tm.getFamilyName().orElse(null),
                    tm.getPhoneNumber().orElse(null),
                    tm.getEmailAddress().orElse(null)
            );
        }
    }

    /**
     * Result returned from the tool.
     */
    public record SquareTeamMembersResult(
            List<Employee> employees,
            Status status,
            String message
            ) {

    }
}
