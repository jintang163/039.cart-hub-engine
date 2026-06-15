package com.carhub.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Data
@Configuration
@ConfigurationProperties(prefix = "cart-hub")
public class CartHubProperties {

    private Redis redis = new Redis();
    private Validate validate = new Validate();
    private Minio minio = new Minio();
    private Sync sync = new Sync();
    private Limit limit = new Limit();
    private Promotion promotion = new Promotion();

    @Data
    public static class Redis {
        private Integer cartExpireSeconds = 864000;
        private Integer shareExpireSeconds = 86400;
        private Integer snapshotExpireSeconds = 2592000;
    }

    @Data
    public static class Validate {
        private Boolean enable = true;
        private String validateUrl;
        private Integer timeoutMs = 3000;
        private Integer cacheSeconds = 300;
    }

    @Data
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucketSnapshot = "cart-snapshot";
        private String bucketLog = "cart-log";
    }

    @Data
    public static class Sync {
        private Boolean enableDbSync = true;
        private Integer syncIntervalSeconds = 300;
    }

    @Data
    public static class Limit {
        private Integer maxCartSize = 200;
        private Integer maxItemQuantity = 999;
    }

    @Data
    public static class Promotion {
        private Boolean enable = true;
        private String calculateUrl;
        private Integer timeoutMs = 5000;
        private String couponListUrl;
        private String promotionListUrl;
        private Integer cacheSeconds = 60;
    }

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(promotion.getTimeoutMs());
        factory.setReadTimeout(promotion.getTimeoutMs());
        return new RestTemplate(factory);
    }

}
