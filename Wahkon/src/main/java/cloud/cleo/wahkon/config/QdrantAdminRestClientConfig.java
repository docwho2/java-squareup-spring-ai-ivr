package cloud.cleo.wahkon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class QdrantAdminRestClientConfig {

    @Bean(name = "qdrantAdminRestClient")
    public RestClient qdrantAdminRestClient(QdrantProperties props) {
        var builder = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        // Qdrant Cloud uses "api-key" header
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            builder = builder.defaultHeader("api-key", props.apiKey());
        }

        return builder.build();
    }
}