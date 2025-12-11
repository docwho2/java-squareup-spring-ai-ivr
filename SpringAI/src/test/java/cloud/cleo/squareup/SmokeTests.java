package cloud.cleo.squareup;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Link;
import lombok.extern.log4j.Log4j2;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Some basic tests to run after a deployment and on a schedule just to validate 
 * we can talk to Square via API and test the path from Lex to Lambda to model.
 * 
 * @author sjensen
 */
@Log4j2
@Tag("smoke")
@Epic("Smoke Tests")
public class SmokeTests extends AbstractLexAwsTestSupport {
    
    
    @Test
    @Order(1)
    @Feature("SquareAPI")
    @Feature("Tool Call")
    @DisplayName("Chuckles Candy Test")
    @Link("https://github.com/docwho2/java-squareup-spring-ai-ivr/blob/main/SpringAI/src/main/java/cloud/cleo/squareup/tools/SquareItemSearch.java")
    void chucklesCandyTest() {

        final var res = sendToLex(
                "Do you have Chuckles Candy in stock?"
        );
        
        final var chuckles = getBotResponse(res);
        
        boolean ok = chuckles.matches("(?s).*?(Yes|We have|Chuckles).*");
        log.info(ok ? "Chuckles Test Passed" : "Chuckles Test FAILED");
        assertTrue(ok, "Chuckles test failed, response was: " + chuckles);
    }

    @Test
    @Order(2)
    @Feature("Store Knowledge")
    @DisplayName("Restaurant Recommendation Test")
    void restaurantTest() {

        final var res = sendToLex(
                "Please recommend a restaurant in the area?"
        );
        
        final var muggs = getBotResponse(res);
        
        boolean ok = muggs.toLowerCase().contains("mugg");
        log.info(ok ? "Muggs Restaurant Test Passed" : "Muggs Restaurant Test FAILED");
        assertTrue(ok, "Muggs restaurant test failed, response was: " + muggs);
    }

    @Test
    @Order(3)
    @Feature("Store Knowledge")
    @DisplayName("Address Test")
    void addressTest() {

        final var res = sendToLex(
                "What is your address?"
        );
        
        final var address = getBotResponse(res);
        
        boolean ok = address.matches("(?s).*160\\s+Main.*");
        log.info(ok ? "Address Test Passed" : "Address Test FAILED");
        assertTrue(ok, "Address test failed, response was: " + address);
    }

    @Test
    @Order(4)
    @Feature("SquareAPI")
    @Feature("Tool Call")
    @DisplayName("Staff Test")
    @Link("https://github.com/docwho2/java-squareup-spring-ai-ivr/blob/main/SpringAI/src/main/java/cloud/cleo/squareup/tools/SquareTeamMembers.java")
    void staffTest() {

        final var res = sendToLex(
                "Does Steve work there?  If so, just say Yes"
        );
        
        final var staff = getBotResponse(res);
        
        boolean ok = staff.matches("(?is).*?(jensen|yes|copperfoxgifts|indeed|confirm).*");
        log.info(ok ? "Staff Test Passed" : "Staff Test FAILED");
        assertTrue(ok, "Staff test failed, response was: " + staff);
    }
}
