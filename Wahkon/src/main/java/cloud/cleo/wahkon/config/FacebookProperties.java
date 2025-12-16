package cloud.cleo.wahkon.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

@Validated
@ConfigurationProperties(prefix = "wahkon.facebook")
public record FacebookProperties(
        @NotBlank
        String baseUrl, // https://graph.facebook.com
        @NotBlank
        String apiVersion, // v24.0 (or whatever you pin)
        @NotBlank
        String accessToken, // long-lived token
        Duration timeout,
        int maxApiPages,
        @NotEmpty
        List<Page> pages
        ) {

    public record Page(
            @NotBlank
            String name, // metadata "source" like "wahkon"
            @NotBlank
            String pageId // FB Page ID (not username)
            ) {

    }

    public String apiBase() {
        return baseUrl + "/" + apiVersion;
    }
}
