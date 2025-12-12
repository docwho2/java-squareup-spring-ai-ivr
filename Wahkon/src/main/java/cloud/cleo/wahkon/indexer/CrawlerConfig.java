package cloud.cleo.wahkon.indexer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CrawlerProperties.class)
class CrawlerConfig {
}
