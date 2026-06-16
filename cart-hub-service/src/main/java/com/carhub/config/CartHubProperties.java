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
    private Recommend recommend = new Recommend();
    private Remark remark = new Remark();
    private Share share = new Share();
    private Checkout checkout = new Checkout();

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

    @Data
    public static class Recommend {
        private Boolean enable = true;
        private String recommendUrl;
        private Integer timeoutMs = 3000;
        private Integer topN = 10;
        private Double minConfidence = 0.1;
        private Double minSupport = 0.01;
        private Integer cacheSeconds = 300;
        private Integer analyzeIntervalHours = 6;
        private Integer analyzeRecentDays = 30;
        private Integer maxHistoryRecords = 50000;
    }

    @Data
    public static class Remark {
        private Boolean enable = true;
        private Integer maxLength = 200;
        private Boolean enableSensitiveWordFilter = true;
        private java.util.List<String> sensitiveWords = new java.util.ArrayList<>();
    }

    @Data
    public static class Share {
        private String baseUrl = "https://example.com/cart/share";
        private String qrCodeApi = "https://api.qrserver.com/v1/create-qr-code/?size=256x256&data=";
        private Boolean enableQrCode = true;
    }

    @Data
    public static class Checkout {
        private Integer expireMinutes = 15;
        private String stockLockUrl;
        private String stockReleaseUrl;
        private Integer stockTimeoutMs = 3000;
        private Boolean enableStockLock = false;
        private Integer maxConcurrentPerUser = 3;
        private String notifyUrl;
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
