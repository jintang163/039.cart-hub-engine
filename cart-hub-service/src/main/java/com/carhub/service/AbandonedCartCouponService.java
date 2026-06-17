package com.carhub.service;

import com.alibaba.fastjson.JSON;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.AnalyticsProperties;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AbandonedCartCouponService {

    private final AnalyticsProperties analyticsProperties;
    private final CartRedisStorage cartRedisStorage;
    private final RedissonClient redissonClient;
    private final RestTemplate restTemplate;

    private static final String COUPON_SENT_KEY_PREFIX = "cart:abandoned:coupon:sent:";
    private static final String SCAN_LOCK_KEY = "cart:abandoned:coupon:scan:lock";
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private CartService cartService;

    @Scheduled(cron = "${analytics.abandoned-cart.check-cron:0 */30 * * * ?}")
    public void scheduledAbandonedCartScan() {
        if (!analyticsProperties.getAbandonedCart().isEnable()) {
            return;
        }

        var lock = redissonClient.getLock(SCAN_LOCK_KEY);
        try {
            boolean acquired = lock.tryLock(0, 30, TimeUnit.MINUTES);
            if (!acquired) {
                log.info("Abandoned cart coupon scan already running, skip this round");
                return;
            }

            log.info("Starting abandoned cart coupon scan");
            int processed = scanAndSendCoupons();
            log.info("Abandoned cart coupon scan completed, processed {} users", processed);

        } catch (Exception e) {
            log.error("Abandoned cart coupon scan failed", e);
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public int triggerManualScan() {
        log.info("Manual abandoned cart coupon scan triggered");
        return scanAndSendCoupons();
    }

    private int scanAndSendCoupons() {
        int thresholdMinutes = analyticsProperties.getAbandonedCart().getThresholdMinutes();
        int processed = 0;

        try {
            Set<String> userIds = findAbandonedCartUsers(thresholdMinutes);
            log.info("Found {} users with potentially abandoned carts", userIds.size());

            for (String userId : userIds) {
                try {
                    if (hasReceivedCoupon(userId)) {
                        continue;
                    }

                    if (processAbandonedCart(userId)) {
                        markCouponSent(userId);
                        processed++;
                    }
                } catch (Exception e) {
                    log.error("Process abandoned cart failed for user: {}", userId, e);
                }
            }
        } catch (Exception e) {
            log.error("Scan abandoned carts failed", e);
        }

        return processed;
    }

    private Set<String> findAbandonedCartUsers(int thresholdMinutes) {
        Set<String> userIds = new HashSet<>();

        String tenantId = "default";
        String bizType = "ecommerce";

        try {
            String pattern = "cart:items:" + tenantId + ":" + bizType + ":*";
            var keys = redissonClient.getKeys().getKeysByPattern(pattern, 1000);

            for (String key : keys) {
                try {
                    String userId = extractUserIdFromKey(key);
                    if (StringUtils.isBlank(userId)) {
                        continue;
                    }

                    Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
                    if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
                        continue;
                    }

                    if (isAbandoned(cart, thresholdMinutes)) {
                        userIds.add(userId);
                    }
                } catch (Exception e) {
                    log.warn("Check cart abandonment failed for key: {}", key, e);
                }
            }
        } catch (Exception e) {
            log.error("Find abandoned cart users failed", e);
        }

        return userIds;
    }

    private String extractUserIdFromKey(String key) {
        try {
            String[] parts = key.split(":");
            if (parts.length >= 5) {
                return parts[4];
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isAbandoned(Cart cart, int thresholdMinutes) {
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return false;
        }

        Long lastAccessTime = cart.getLastAccessTime();
        if (lastAccessTime == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long minutesSinceLastAccess = (currentTime - lastAccessTime) / (1000 * 60);

        return minutesSinceLastAccess >= thresholdMinutes;
    }

    private boolean processAbandonedCart(String userId) {
        String tenantId = "default";
        String bizType = "ecommerce";

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return false;
        }

        boolean couponSent = sendAbandonedCartCoupon(tenantId, bizType, userId, cart);
        if (couponSent) {
            sendAbandonedCartNotification(tenantId, bizType, userId, cart);
            return true;
        }

        return false;
    }

    private boolean sendAbandonedCartCoupon(String tenantId, String bizType, String userId, Cart cart) {
        try {
            String couponTemplateId = analyticsProperties.getAbandonedCart().getCouponTemplateId();
            if (StringUtils.isBlank(couponTemplateId)) {
                log.warn("Coupon template not configured, skip sending coupon");
                return false;
            }

            BigDecimal totalAmount = cart.getTotalAmount() != null ? cart.getTotalAmount() : BigDecimal.ZERO;
            int itemCount = cart.getItemCount() != null ? cart.getItemCount() : 0;

            Map<String, Object> couponData = new HashMap<>();
            couponData.put("tenantId", tenantId);
            couponData.put("bizType", bizType);
            couponData.put("userId", userId);
            couponData.put("couponTemplateId", couponTemplateId);
            couponData.put("source", "abandoned_cart");
            couponData.put("cartTotalAmount", totalAmount);
            couponData.put("cartItemCount", itemCount);
            couponData.put("sendTime", LocalDateTime.now().format(DATE_TIME_FMT));

            String couponApiUrl = analyticsProperties.getAbandonedCart().getNotifyApiUrl();
            if (StringUtils.isBlank(couponApiUrl)) {
                log.warn("Coupon API URL not configured, skip sending coupon");
                return false;
            }

            String couponEndpoint = couponApiUrl.replace("/notify/send", "/coupon/issue");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(JsonUtil.toJson(couponData), headers);

            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        couponEndpoint, HttpMethod.POST, request, String.class);
                if (response.getStatusCode() == HttpStatus.OK) {
                    log.info("Abandoned cart coupon sent: userId={}, template={}", userId, couponTemplateId);
                    return true;
                } else {
                    log.warn("Coupon API returned non-200 status: {}, body: {}",
                            response.getStatusCode(), response.getBody());
                    return false;
                }
            } catch (Exception e) {
                log.warn("Call coupon API failed, fallback to notification only: {}", e.getMessage());
                return true;
            }

        } catch (Exception e) {
            log.error("Send abandoned cart coupon failed: userId={}", userId, e);
            return false;
        }
    }

    private void sendAbandonedCartNotification(String tenantId, String bizType, String userId, Cart cart) {
        try {
            String channels = analyticsProperties.getAbandonedCart().getNotifyChannels();
            String notifyApiUrl = analyticsProperties.getAbandonedCart().getNotifyApiUrl();

            if (StringUtils.isBlank(channels) || StringUtils.isBlank(notifyApiUrl)) {
                return;
            }

            String[] channelArray = channels.split(",");
            for (String channel : channelArray) {
                try {
                    Map<String, Object> payload = buildAbandonedCartPayload(tenantId, bizType, userId, cart, channel.trim());
                    doSendNotification(notifyApiUrl, payload);
                } catch (Exception e) {
                    log.error("Send {} abandoned cart notification failed: userId={}", channel, userId, e);
                }
            }
            log.info("Abandoned cart notification sent: userId={}, channels={}", userId, channels);
        } catch (Exception e) {
            log.error("Send abandoned cart notification error: userId={}", userId, e);
        }
    }

    private Map<String, Object> buildAbandonedCartPayload(String tenantId, String bizType,
                                                            String userId, Cart cart, String channel) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("bizType", bizType);
        payload.put("userId", userId);
        payload.put("templateType", "abandoned_cart_coupon");
        payload.put("channel", channel);

        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("itemCount", cart.getItemCount() != null ? cart.getItemCount() : 0);
        templateParams.put("totalAmount", cart.getTotalAmount() != null ? cart.getTotalAmount() : BigDecimal.ZERO);
        templateParams.put("couponTemplateId", analyticsProperties.getAbandonedCart().getCouponTemplateId());

        if (cart.getItems() != null && !cart.getItems().isEmpty()) {
            List<Map<String, Object>> itemList = new ArrayList<>();
            int displayCount = Math.min(cart.getItems().size(), 3);
            for (int i = 0; i < displayCount; i++) {
                CartItem item = cart.getItems().get(i);
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("skuId", item.getSkuId());
                itemMap.put("itemName", item.getItemName());
                itemMap.put("itemImage", item.getItemImage());
                itemMap.put("quantity", item.getQuantity());
                itemMap.put("unitPrice", item.getUnitPrice());
                itemList.add(itemMap);
            }
            templateParams.put("items", itemList);
            if (cart.getItems().size() > 3) {
                templateParams.put("moreCount", cart.getItems().size() - 3);
            }
        }

        payload.put("templateParams", templateParams);
        return payload;
    }

    private void doSendNotification(String notifyUrl, Map<String, Object> payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(JsonUtil.toJson(payload), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    notifyUrl, HttpMethod.POST, request, String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                log.debug("Notification sent successfully: {}", payload.get("templateType"));
            } else {
                log.warn("Notification API returned non-200 status: {}, body: {}",
                        response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.warn("Call notification API failed: {}", e.getMessage());
        }
    }

    private boolean hasReceivedCoupon(String userId) {
        try {
            String key = COUPON_SENT_KEY_PREFIX + userId;
            RSet<String> sentSet = redissonClient.getSet(key);
            return sentSet.contains(LocalDateTime.now().toLocalDate().toString());
        } catch (Exception e) {
            log.warn("Check coupon sent status failed: userId={}", userId, e);
            return false;
        }
    }

    private void markCouponSent(String userId) {
        try {
            String key = COUPON_SENT_KEY_PREFIX + userId;
            RSet<String> sentSet = redissonClient.getSet(key);
            sentSet.add(LocalDateTime.now().toLocalDate().toString());
            sentSet.expire(30, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Mark coupon sent failed: userId={}", userId, e);
        }
    }

    public Map<String, Object> getStatistics(String tenantId, String bizType, String date) {
        Map<String, Object> result = new HashMap<>();

        if (StringUtils.isBlank(date)) {
            date = LocalDateTime.now().toLocalDate().toString();
        }

        result.put("tenantId", tenantId);
        result.put("bizType", bizType);
        result.put("date", date);
        result.put("thresholdMinutes", analyticsProperties.getAbandonedCart().getThresholdMinutes());
        result.put("couponTemplateId", analyticsProperties.getAbandonedCart().getCouponTemplateId());
        result.put("enabled", analyticsProperties.getAbandonedCart().isEnable());

        try {
            String pattern = COUPON_SENT_KEY_PREFIX + "*";
            var keys = redissonClient.getKeys().getKeysByPattern(pattern, 1000);
            int totalSent = 0;
            for (String key : keys) {
                RSet<String> sentSet = redissonClient.getSet(key);
                if (sentSet.contains(date)) {
                    totalSent++;
                }
            }
            result.put("couponsSentToday", totalSent);
        } catch (Exception e) {
            log.warn("Get coupon statistics failed", e);
            result.put("couponsSentToday", 0);
        }

        return result;
    }
}
