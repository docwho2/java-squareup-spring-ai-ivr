package cloud.cleo.wahkon.config;

import cloud.cleo.wahkon.cloudfunctions.ScheduledFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@RequiredArgsConstructor
public class LocalRunConfig {

    private final ScheduledFunction cloudWatchFunction;
    
    @Bean
    @Profile("local")
    CommandLineRunner runOnceLocal() {
        return args -> cloudWatchFunction.apply(new ScheduledFunction.ScheduleInput("ALL"));
    }
}