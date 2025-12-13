package cloud.cleo.wahkon.config;

import cloud.cleo.wahkon.service.WahkonWebCrawlerService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
public class LocalRunConfig {

    @Bean
    @Profile("local")
    CommandLineRunner runOnceLocal(WahkonWebCrawlerService crawler) {
        return args -> crawler.crawlAll();
    }
}