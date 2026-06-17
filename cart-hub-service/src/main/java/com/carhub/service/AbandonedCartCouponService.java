package com.carhub.service;

import com.carhub.common.util.JsonUtil;
import com.carhub.config.AnalyticsProperties;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.storage.CartRedisStorage;
import com.clickhouse.jdbc.ClickHouseDataSource;
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
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final Pattern CART_KEY_PATTERN = Pattern.compile("cart:\\{([^}]+)\\}:\\{([^}]+)\\}:\\{([^}]+)\\}");

    @Resource
    private CartService cartService;

    @Resource
    private ClickHouseDataSource clickHouseDataSource;

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

    public int triggerManualScan(String tenantId, String bizType) {
        log.info("Manual abandoned cart coupon scan triggered, tenantId={}, bizType={}", tenantId, bizType);
        return scanAndSendCouponsForTenant(tenantId, bizType);
    }

    public int triggerManualScanAll() {
        log.info("Manual abandoned cart coupon scan triggered for all tenants");
        return scanAndSendCoupons();
    }

    private int scanAndSendCoupons() {
        return scanAndSendCouponsForTenant(null, null);
    }

    private int scanAndSendCouponsForTenant(String targetTenantId, String targetBizType) {
        int thresholdMinutes = analyticsProperties.getAbandonedCart().getThresholdMinutes();
        int processed = 0;

        try {
            List<TenantUserKey> abandonedKeys = findAbandonedCartKeys(targetTenantId, targetBizType, thresholdMinutes);
            log.info("Found {} users with potentially abandoned carts across tenants", abandonedKeys.size());

            for (TenantUserKey key : abandonedKeys) {
                try {
                    String sentKey = buildSentKey(key.tenantId, key.bizType, key.userId);
                    if (hasReceivedCoupon(sentKey)) {
                        continue;
                    }

                    if (hasCompletedOrder(key.tenantId, key.bizType, key.userId, thresholdMinutes)) {
                        log.debug("User has completed order recently, skip: tenantId={}, bizType={}, userId={}",
                                key.tenantId, key.bizType, key.userId);
                        continue;
                    }

                    if (processAbandonedCart(key.tenantId, key.bizType, key.userId)) {
                        markCouponSent(sentKey);
                        processed++;
                    }
                } catch (Exception e) {
                    log.error("Process abandoned cart failed for user: tenantId={}, bizType={}, userId={}",
                            key.tenantId, key.bizType, key.userId, e);
                }
            }
        } catch (Exception e) {
            log.error("Scan abandoned carts failed", e);
        }

        return processed;
    }

    private List<TenantUserKey> findAbandonedCartKeys(String targetTenantId, String targetBizType, int thresholdMinutes) {
        List<TenantUserKey> result = new ArrayList<>();

        try {
            String pattern = "cart:*";
            if (StringUtils.isNotBlank(targetTenantId)) {
                if (StringUtils.isNotBlank(targetBizType)) {
                    pattern = "cart:{" + targetTenantId + "}:{" + targetBizType + "}:*";
                } else {
                    pattern = "cart:{" + targetTenantId + "}:*";
                }
            }

            var keys = redissonClient.getKeys().getKeysByPattern(pattern, 2000);
            log.info("Scanning cart keys with pattern: {}, matched keys: {}", pattern, getKeysCount(keys));

            for (String key : keys) {
                try {
                    TenantUserKey userKey = parseCartKey(key);
                    if (userKey == null) {
                        continue;
                    }

                    Cart cart = cartRedisStorage.getCart(userKey.tenantId, userKey.bizType, userKey.userId);
                    if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
                        continue;
                    }

                    if (isAbandoned(cart, thresholdMinutes)) {
                        result.add(userKey);
                    }
                } catch (Exception e) {
                    log.warn("Check cart abandonment failed for key: {}", key, e);
                }
            }
        } catch (Exception e) {
            log.error("Find abandoned cart users failed", e);
        }

        return result;
    }

    private int getKeysCount(Iterable<String> keys) {
        int count = 0;
        for (String ignored : keys) {
            count++;
        }
        return count;
    }

    private TenantUserKey parseCartKey(String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }
        String cleanKey = key;
        if (cleanKey.startsWith("cart:total:") || cleanKey.startsWith("cart:meta:")
                || cleanKey.startsWith("cart:share:") || cleanKey.startsWith("cart:snapshot:")
                || cleanKey.startsWith("cart:stat:") || cleanKey.startsWith("cart:last_access:")
                || cleanKey.startsWith("cart:expire_remind:") || cleanKey.startsWith("cart:checkout:")
                || cleanKey.startsWith("cart:recommend:") || cleanKey.startsWith("cart:abandoned:")
                || cleanKey.startsWith("cart:price_drop:") || cleanKey.startsWith("cart:import:")) {
            return null;
        }

        Matcher matcher = CART_KEY_PATTERN.matcher(key);
        if (matcher.matches()) {
            return new TenantUserKey(matcher.group(1), matcher.group(2), matcher.group(3));
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

    private boolean hasCompletedOrder(String tenantId, String bizType, String userId, int thresholdMinutes) {
        if (clickHouseDataSource == null) {
            return false;
        }
        try {
            String startDate = LocalDateTime.now().minusMinutes((long) thresholdMinutes * 3).format(DATE_FMT);
            String endDate = LocalDate.now().format(DATE_FMT);

            String sql = "SELECT count(*) as cnt FROM cart_events " +
                    "WHERE tenant_id = ? AND biz_type = ? AND user_id = ? " +
                    "  AND event_type = 'checkout_confirm' " +
                    "  AND date >= ? AND date <= ? " +
                    "  AND timestamp >= now() - INTERVAL ? MINUTE";

            try (Connection conn = clickHouseDataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, tenantId);
                pstmt.setString(2, bizType);
                pstmt.setString(3, userId);
                pstmt.setString(4, startDate);
                pstmt.setString(5, endDate);
                pstmt.setInt(6, thresholdMinutes * 3);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("cnt") > 0;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Check completed order failed, fallback to skip: tenantId={}, userId={}",
                    tenantId, userId, e);
        }
        return false;
    }

    private boolean processAbandonedCart(String tenantId, String bizType, String userId) {
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
                    log.info("Abandoned cart coupon sent: tenantId={}, bizType={}, userId={}, template={}",
                            tenantId, bizType, userId, couponTemplateId);
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
            log.error("Send abandoned cart coupon failed: tenantId={}, userId={}", tenantId, userId, e);
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
                    log.error("Send {} abandoned cart notification failed: tenantId={}, userId={}",
                            channel, tenantId, userId, e);
                }
            }
            log.info("Abandoned cart notification sent: tenantId={}, bizType={}, userId={}, channels={}",
                    tenantId, bizType, userId, channels);
        } catch (Exception e) {
            log.error("Send abandoned cart notification error: tenantId={}, userId={}", tenantId, userId, e);
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

    private String buildSentKey(String tenantId, String bizType, String userId) {
        return COUPON_SENT_KEY_PREFIX + tenantId + ":" + bizType + ":" + userId;
    }

    private boolean hasReceivedCoupon(String sentKey) {
        try {
            RSet<String> sentSet = redissonClient.getSet(sentKey);
            return sentSet.contains(LocalDateTime.now().toLocalDate().toString());
        } catch (Exception e) {
            log.warn("Check coupon sent status failed: sentKey={}", sentKey, e);
            return false;
        }
    }

    private void markCouponSent(String sentKey) {
        try {
            RSet<String> sentSet = redissonClient.getSet(sentKey);
            sentSet.add(LocalDateTime.now().toLocalDate().toString());
            sentSet.expire(30, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Mark coupon sent failed: sentKey={}", sentKey, e);
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

        int todaySent = 0;
        int totalSent = 0;
        Set<String> tenantBizTypes = new LinkedHashSet<>();

        try {
            String pattern;
            if (StringUtils.isNotBlank(tenantId)) {
                if (StringUtils.isNotBlank(bizType)) {
                    pattern = COUPON_SENT_KEY_PREFIX + tenantId + ":" + bizType + ":*";
                } else {
                    pattern = COUPON_SENT_KEY_PREFIX + tenantId + ":*";
                }
            } else {
                pattern = COUPON_SENT_KEY_PREFIX + "*";
            }

            var keys = redissonClient.getKeys().getKeysByPattern(pattern, 2000);
            String finalDate = date;
            for (String key : keys) {
                try {
                    String[] parts = key.replace(COUPON_SENT_KEY_PREFIX, "").split(":");
                    if (parts.length >= 2) {
                        tenantBizTypes.add(parts[0] + "/" + parts[1]);
                    }
                    RSet<String> sentSet = redissonClient.getSet(key);
                    Set<String> allDates = sentSet.readAll();
                    totalSent += allDates.size();
                    if (allDates.contains(finalDate)) {
                        todaySent++;
                    }
                } catch (Exception e) {
                    log.warn("Read coupon sent set failed for key: {}", key, e);
                }
            }
            result.put("couponsSentToday", todaySent);
            result.put("couponsSentTotal30d", totalSent);
            result.put("tenantBizTypes", new ArrayList<>(tenantBizTypes));
        } catch (Exception e) {
            log.warn("Get coupon statistics failed", e);
            result.put("couponsSentToday", 0);
            result.put("couponsSentTotal30d", 0);
            result.put("tenantBizTypes", Collections.emptyList());
        }

        try {
            String pattern;
            if (StringUtils.isNotBlank(tenantId)) {
                if (StringUtils.isNotBlank(bizType)) {
                    pattern = "cart:{" + tenantId + "}:{" + bizType + "}:*";
                } else {
                    pattern = "cart:{" + tenantId + "}:*";
                }
            } else {
                pattern = "cart:*";
            }
            int thresholdMinutes = analyticsProperties.getAbandonedCart().getThresholdMinutes();
            int abandonedCount = 0;
            int activeCount = 0;
            var keys = redissonClient.getKeys().getKeysByPattern(pattern, 2000);
            for (String key : keys) {
                try {
                    TenantUserKey userKey = parseCartKey(key);
                    if (userKey == null) continue;
                    Cart cart = cartRedisStorage.getCart(userKey.tenantId, userKey.bizType, userKey.userId);
                    if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) continue;
                    if (isAbandoned(cart, thresholdMinutes)) {
                        abandonedCount++;
                    } else {
                        activeCount++;
                    }
                } catch (Exception ignored) {
                }
            }
            result.put("abandonedCartCount", abandonedCount);
            result.put("activeCartCount", activeCount);
        } catch (Exception e) {
            log.warn("Get cart count statistics failed", e);
            result.put("abandonedCartCount", 0);
            result.put("activeCartCount", 0);
        }

        return result;
    }

    private static class TenantUserKey {
        final String tenantId;
        final String bizType;
        final String userId;

        TenantUserKey(String tenantId, String bizType, String userId) {
            this.tenantId = tenantId;
            this.bizType = bizType;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TenantUserKey)) return false;
            TenantUserKey that = (TenantUserKey) o;
            return Objects.equals(tenantId, that.tenantId) &&
                    Objects.equals(bizType, that.bizType) &&
                    Objects.equals(userId, that.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, bizType, userId);
        }
    }
}
