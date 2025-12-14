package cloud.cleo.wahkon.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean(name = "qdrantAdminRestClient")
    public RestClient qdrantAdminRestClient(QdrantProperties props) {
        var builder = RestClient.builder()
                .baseUrl(props.baseAdminUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        // Qdrant Cloud uses "api-key" header
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            builder = builder.defaultHeader("api-key", props.apiKey());
        }

        return builder.build();
    }

    @Bean(name = "pdfRestClient")
    public RestClient pdfRestClient() {

        var httpClient = java.net.http.HttpClient.newBuilder()
                // Need to follow redirects
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; WahkonCrawler/1.0)")
                .build();
    }
}
