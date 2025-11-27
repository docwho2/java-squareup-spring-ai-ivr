/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.pinpoint.PinpointAsyncClient;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.sns.SnsAsyncClient;

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
                // tweak if you want:
                // .maxConcurrency(512)
                // .connectionTimeout(Duration.ofSeconds(1))
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
    public DynamoDbAsyncClient dynamoDbAsyncClient(SdkAsyncHttpClient crtAsyncHttpClient) {
        return DynamoDbAsyncClient.builder()
                .httpClient(crtAsyncHttpClient)
                .build();
    }

    @Bean
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbAsyncClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public CostExplorerAsyncClient costExplorerAsyncClient(SdkAsyncHttpClient crtAsyncHttpClient) {
        return CostExplorerAsyncClient.builder()
                .httpClient(crtAsyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public PinpointAsyncClient pinpointAsyncClient(SdkAsyncHttpClient crtAsyncHttpClient) {
        return PinpointAsyncClient.builder()
                .httpClient(crtAsyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SesAsyncClient sesAsyncClient(SdkAsyncHttpClient crtAsyncHttpClient) {
        return SesAsyncClient.builder()
                .httpClient(crtAsyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SnsAsyncClient snsAsyncClient(SdkAsyncHttpClient crtAsyncHttpClient) {
        return SnsAsyncClient.builder()
                // Force SMS sending to east because that's where all the 10DLC and campaign crap setup is done
                // Otherwise have to pay for registrations and numbers in 2 regions, HUGE HASSLE (and more monthly cost)
                // Also then all texts are sourced from the same phone number for consistancy
                .region(Region.US_EAST_1)
                .httpClient(crtAsyncHttpClient)
                .build();
    }

}
