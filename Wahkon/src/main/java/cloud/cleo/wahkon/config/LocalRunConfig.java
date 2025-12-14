package cloud.cleo.wahkon.config;

import cloud.cleo.wahkon.cloudfunctions.CloudWatchFunction;
import cloud.cleo.wahkon.service.FacebookPipelineService;
import cloud.cleo.wahkon.service.WahkonWebCrawlerService;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@RequiredArgsConstructor
public class LocalRunConfig {

    private final CloudWatchFunction cloudWatchFunction;
    
    @Bean
    @Profile("local")
    CommandLineRunner runOnceLocal(WahkonWebCrawlerService crawler, FacebookPipelineService fb) {
        return args -> cloudWatchFunction.apply(new ScheduledEvent());
    }
}