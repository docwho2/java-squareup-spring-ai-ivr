package cloud.cleo.wahkon.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(prefix = "wahkon.crawler")
public record CrawlerProperties(

        @NotBlank
        String userAgent,

        @Min(1_000)
        @Max(120_000)
        int timeoutMs,

        @Min(0)
        @Max(10)
        int maxDepth,

        @Min(1)
        @Max(10_000)
        int maxPages,

        @Min(1)
        @Max(64)
        int concurrency,

        Duration retentionDuration,

        @NotEmpty
        @Valid
        List<Site> sites
) {

    public record Site(

            @NotBlank
            String name,

            @NotEmpty
            List<@NotBlank String> seeds,

            List<@NotBlank String> allowedHosts,

            String includeUrlRegex,

            String excludeUrlRegex
    ) { }
}