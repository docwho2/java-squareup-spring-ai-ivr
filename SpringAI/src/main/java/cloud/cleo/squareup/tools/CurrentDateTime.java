/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.tools;

import cloud.cleo.squareup.LexV2EventWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CurrentDateTime extends AbstractTool {

    private final ObjectMapper mapper;
    private final ZoneId storeZoneId;

    @Tool(
        name = "get_current_date_time",
        description = "Returns the current date/time in ISO format and timezone."
    )
    public JsonNode getCurrentDateTime() {
        var now = ZonedDateTime.now(storeZoneId);
        var root = mapper.createObjectNode();
        root.put("iso", now.toString());
        root.put("date", now.toLocalDate().toString());
        root.put("time", now.toLocalTime().toString());
        root.put("zone", now.getZone().toString());
        return root;
    }

    @Override
    public boolean isValidForRequest(LexV2EventWrapper event) {
        return true;
    }
}

