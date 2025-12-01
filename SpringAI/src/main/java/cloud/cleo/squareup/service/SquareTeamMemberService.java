package cloud.cleo.squareup.service;

import cloud.cleo.squareup.config.SquareConfig.SquareProperties;
import com.squareup.square.AsyncSquareClient;
import com.squareup.square.types.SearchTeamMembersFilter;
import com.squareup.square.types.SearchTeamMembersQuery;
import com.squareup.square.types.SearchTeamMembersRequest;
import com.squareup.square.types.TeamMember;
import com.squareup.square.types.TeamMemberStatus;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class SquareTeamMemberService {

    private final @Nullable AsyncSquareClient asyncSquareClient;
    private final SquareProperties squareProperties; // from your SquareConfig

    public boolean isEnabled() {
        return asyncSquareClient != null && squareProperties.enabled();
    }

    /**
     * Get active team members for the configured Square location.
     * Returns an empty list if Square is disabled or nothing found.
     * @return 
     */
    public List<TeamMember> getActiveTeamMembers() {
        if (!isEnabled()) {
            log.debug("Square is not enabled; skipping team member lookup.");
            return Collections.emptyList();
        }

        String locationId = squareProperties.locationId();
        if (locationId == null || locationId.isBlank()) {
            log.warn("Square location id is not configured; returning empty team member list.");
            return Collections.emptyList();
        }

        try {
            var request = SearchTeamMembersRequest.builder()
                    .query(SearchTeamMembersQuery.builder()
                            .filter(SearchTeamMembersFilter.builder()
                                    .status(TeamMemberStatus.ACTIVE)
                                    .locationIds(List.of(locationId))
                                    .build())
                            .build())
                    .build();

            var response = asyncSquareClient
                    .teamMembers()
                    .search(request)
                    .get();

            var teamMembersOpt = response.getTeamMembers();
            if (teamMembersOpt.isEmpty()) {
                return Collections.emptyList();
            }

            return teamMembersOpt.get();

        } catch (CompletionException e) {
            log.error("Error retrieving Square team members (wrapped)", e.getCause());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Error retrieving Square team members", e);
            return Collections.emptyList();
        }
    }
}