package cloud.cleo.wahkon.indexer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "wahkon.crawler")
public record CrawlerProperties(
        String userAgent,
        int timeoutMs,
        int maxDepth,
        int maxPages,
        int concurrency,
        int retentionDays,
        List<Site> sites
        ) {

    public record Site(
            String name,
            List<String> seeds,
            List<String> allowedHosts,
            String includeUrlRegex,
            String excludeUrlRegex
            ) {

        public Site {
            if (seeds == null) {
                seeds = new ArrayList<>();
            }
            if (allowedHosts == null) {
                allowedHosts = new ArrayList<>();
            }
        }
    }
}
