/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Build all needed AWS Clients used.
 *
 * @author sjensen
 */
@Configuration
public class AWSConfig {

    /**
     * Use one common CRT client across all AWS Services.
     *
     * @return
     */
    @Bean(destroyMethod = "close")
    public SdkAsyncHttpClient crtAsyncHttpClient() {
        return AwsCrtAsyncHttpClient.create();
    }

    @Bean(destroyMethod = "close")
    public SdkHttpClient crtSyncHttpClient() {
        return AwsCrtHttpClient.builder()
                .build();
    }

    @Bean(destroyMethod = "close")
    public BedrockRuntimeClient bedrockRuntimeClient(SdkHttpClient crtSyncHttpClient) {
        return BedrockRuntimeClient.builder()
                .httpClient(crtSyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(SdkAsyncHttpClient crtAsyncHttpClient) {
        return BedrockRuntimeAsyncClient.builder()
                .httpClient(crtAsyncHttpClient)
                .build();
    }
    
    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(SdkHttpClient crtHttpClient) {
        return DynamoDbClient.builder()
                .httpClient(crtHttpClient)
                .build();
    }
    
    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public PinpointClient pinpointClient(SdkHttpClient crtHttpClient) {
        return PinpointClient.builder()
                .httpClient(crtHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SesClient sesClient(SdkHttpClient crtHttpClient) {
        return SesClient.builder()
                .httpClient(crtHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SnsClient snsClient(SdkHttpClient crtHttpClient) {
        return SnsClient.builder()
                // Force SMS sending to east because that's where all the 10DLC and campaign crap setup is done
                // Otherwise have to pay for registrations and numbers in 2 regions, HUGE HASSLE (and more monthly cost)
                // Also then all texts are sourced from the same phone number for consistancy
                .region(Region.US_EAST_1)
                .httpClient(crtHttpClient)
                .build();
    }

    
    @Bean
    public RestClient facebookRestClient(RestClient.Builder builder) {
        return builder
                .baseUrl("https://graph.facebook.com")
                .build();
    }
}
