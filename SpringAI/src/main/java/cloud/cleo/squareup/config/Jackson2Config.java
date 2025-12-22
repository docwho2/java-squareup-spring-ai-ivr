package cloud.cleo.squareup.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class Jackson2Config {

    /**
     * Explicit Jackson 2.x ObjectMapper for Spring AI / Bedrock.
     *
     * This intentionally coexists with Jackson 3 (tools.jackson.*)
     * used by Spring Cloud Function.
     * @return 
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }
}
