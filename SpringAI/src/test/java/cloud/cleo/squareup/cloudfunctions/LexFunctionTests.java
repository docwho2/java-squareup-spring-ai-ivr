package cloud.cleo.squareup.cloudfunctions;


import cloud.cleo.squareup.LexV2Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LexFunctionTests {

    @Autowired
    LexFunction lexFunction;

    @Test
    void chucklesTest() {
        LexV2Event event = null; //LexTestBuilders.simpleTextEvent("Do you have Chuckles Candy in stock?");
        var response = lexFunction.apply(event);

        var msg = response.getMessages().getFirst().getContent().toLowerCase();

        assertThat(msg).contains("chuckles");
        assertThat(msg).containsAnyOf("yes", "we have", "in stock");
    }
}