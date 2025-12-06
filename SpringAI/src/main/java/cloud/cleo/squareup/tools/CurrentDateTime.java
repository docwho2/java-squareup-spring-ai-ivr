/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentDateTime extends AbstractTool {

    private final ZoneId storeZoneId;

    @Tool(
            name = "get_current_date_time",
            description = "Returns the current date/time in ISO format and timezone."
    )
    public CurrentDateTimeResult getCurrentDateTime() {
        var now = ZonedDateTime.now(storeZoneId);

        return new CurrentDateTimeResult(
                now.toString(),
                now.toLocalDate().toString(),
                now.toLocalTime().toString(),
                now.getZone().toString()
        );
    }

    public record CurrentDateTimeResult(
            String iso,
            String date,
            String time,
            String zone
            ) {

    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return true;
    }
}
