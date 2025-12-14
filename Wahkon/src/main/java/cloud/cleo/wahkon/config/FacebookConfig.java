package cloud.cleo.wahkon.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FacebookProperties.class)
public class FacebookConfig {
}