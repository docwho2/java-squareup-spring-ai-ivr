/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
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

    @Bean(name = "crt", destroyMethod = "close")
    public SdkHttpClient crtSyncHttpClient() {
        return AwsCrtHttpClient.builder().build();
    }
    
    @Bean(name = "apache", destroyMethod = "close")
    public SdkHttpClient apacheSyncHttpClient() {
        return ApacheHttpClient.builder().build();
    }

   
    @Bean(name = "crtAsync", destroyMethod = "close")
    public SdkAsyncHttpClient crtAsyncHttpClient() {
        return AwsCrtAsyncHttpClient.create();
    }
    
    @Bean(name = "nettyAsync", destroyMethod = "close")
    public SdkAsyncHttpClient nettyAsyncHttpClient() {
        return NettyNioAsyncHttpClient.create();
    }
    
    @Bean(destroyMethod = "close")
    public BedrockRuntimeClient bedrockRuntimeClient( @Qualifier("apache") SdkHttpClient sdkHttpClient) {
        return BedrockRuntimeClient.builder()
                .httpClient(sdkHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(@Qualifier("nettyAsync") SdkAsyncHttpClient bedrockAsyncClient) {
        return BedrockRuntimeAsyncClient.builder()
                .httpClient(bedrockAsyncClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(@Qualifier("crt") SdkHttpClient crtSyncHttpClient) {
        return DynamoDbClient.builder()
                .httpClient(crtSyncHttpClient)
                .build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public PinpointClient pinpointClient(@Qualifier("crt") SdkHttpClient crtSyncHttpClient) {
        return PinpointClient.builder()
                .httpClient(crtSyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SesClient sesClient(@Qualifier("crt") SdkHttpClient crtSyncHttpClient) {
        return SesClient.builder()
                .httpClient(crtSyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SnsClient snsClient(@Qualifier("crt") SdkHttpClient crtSyncHttpClient) {
        return SnsClient.builder()
                // Pin to East since we only have pinpoint numbers there to send SMS
                .region(Region.US_EAST_1)
                .httpClient(crtSyncHttpClient)
                .build();
    }
}

