package com.carhub.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "analytics")
public class AnalyticsProperties {

    private String kafkaTopic = "cart_event_topic";

    private boolean enableKafkaConsumer = true;

    private AbandonedCartProperties abandonedCart = new AbandonedCartProperties();

    @Data
    public static class AbandonedCartProperties {
        private boolean enable = true;
        private int thresholdMinutes = 60;
        private String checkCron = "0 */30 * * * ?";
        private String couponTemplateId = "ABANDONED_CART_COUPON";
        private String notifyChannels = "wechat,sms";
        private String notifyApiUrl = "http://localhost:8081/notify/send";
    }
}
