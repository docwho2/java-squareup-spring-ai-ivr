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
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.crt.AwsCrtHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
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

    @Bean(destroyMethod = "close")
    public SdkHttpClient crtSyncHttpClient() {
        return AwsCrtHttpClient.builder().build();
    }

    /**
     * Bedrock embeddings require HTTP/2. CRT sync doesn't support HTTP/2, so we use Netty async for Bedrock.
     * @return 
     */
    @Bean(name = "bedrockHttp2AsyncClient", destroyMethod = "close")
    public SdkAsyncHttpClient bedrockHttp2AsyncClient() {
        return NettyNioAsyncHttpClient.builder().build();
    }

    @Bean(destroyMethod = "close")
    public BedrockRuntimeAsyncClient bedrockRuntimeAsyncClient(
            @Qualifier("bedrockHttp2AsyncClient") SdkAsyncHttpClient bedrockHttp2AsyncClient
    ) {
        return BedrockRuntimeAsyncClient.builder()
                .httpClient(bedrockHttp2AsyncClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient(SdkHttpClient crtSyncHttpClient) {
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
    public PinpointClient pinpointClient(SdkHttpClient crtSyncHttpClient) {
        return PinpointClient.builder()
                .httpClient(crtSyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SesClient sesClient(SdkHttpClient crtSyncHttpClient) {
        return SesClient.builder()
                .httpClient(crtSyncHttpClient)
                .build();
    }

    @Bean(destroyMethod = "close")
    public SnsClient snsClient(SdkHttpClient crtSyncHttpClient) {
        return SnsClient.builder()
                .region(Region.US_EAST_1)
                .httpClient(crtSyncHttpClient)
                .build();
    }
}

