package com.carhub.service;

import com.carhub.common.constant.CartConstant;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.ProductValidateDTO;
import com.carhub.domain.entity.BizConfigEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartPriceDropNotifyService {

    private final CartHubProperties cartHubProperties;
    private final CartRedisStorage cartRedisStorage;
    private final BizConfigService bizConfigService;
    private final CartValidateService cartValidateService;
    private final CartNotificationService cartNotificationService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Scheduled(cron = "${cart-hub.price-drop.cron:0 0 */3 * * ?}")
    public void scheduledPriceDropTask() {
        if (!Boolean.TRUE.equals(cartHubProperties.getPriceDrop().getEnable())) {
            return;
        }
        RLock lock = redissonClient.getLock(RedisKeyConstant.CART_PRICE_DROP_LOCK_KEY);
        try {
            if (!lock.tryLock(0, 30, TimeUnit.MINUTES)) {
                log.info("Price drop task already running, skip");
                return;
            }
            long start = System.currentTimeMillis();
            log.info("Price drop task started");
            doPriceDropScan();
            log.info("Price drop task finished, cost={}ms", System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Price drop task error", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void triggerManualScan() {
        new Thread(this::doPriceDropScan).start();
    }

    private void doPriceDropScan() {
        String tenantId = CartConstant.DEFAULT_TENANT_ID;
        String bizType = CartConstant.BIZ_TYPE_ECOMMERCE;

        if (!isPriceDropEnabled(tenantId, bizType)) {
            return;
        }

        int batchSize = resolveBatchSize(tenantId, bizType);
        int totalSubscriptions = 0;
        int totalNotified = 0;
        int totalCartUpdated = 0;

        while (true) {
            List<String> skuIds = cartRedisStorage.scanAllSubscribedSkuIds(tenantId, bizType, batchSize);
            if (skuIds.isEmpty()) {
                break;
            }

            List<CartValidateService.ProductValidateResult> validateResults =
                    batchQueryPrices(tenantId, bizType, skuIds);
            Map<String, CartValidateService.ProductValidateResult> resultMap =
                    validateResults.stream()
                            .filter(r -> StringUtils.isNotBlank(r.getSkuId()) && r.getCurrentPrice() != null)
                            .collect(Collectors.toMap(
                                    CartValidateService.ProductValidateResult::getSkuId,
                                    r -> r,
                                    (a, b) -> a));

            for (String skuId : skuIds) {
                try {
                    CartValidateService.ProductValidateResult priceInfo = resultMap.get(skuId);
                    if (priceInfo == null) continue;

                    int processed = processSkuPriceDrop(tenantId, bizType, skuId, priceInfo);
                    totalNotified += processed;
                    if (processed > 0) totalSubscriptions++;
                } catch (Exception e) {
                    log.error("Process sku price drop error, skuId={}", skuId, e);
                }
            }
            if (skuIds.size() < batchSize) {
                break;
            }
        }

        totalCartUpdated = autoUpdateCartPrices(tenantId, bizType);
        recordStatistics(tenantId, bizType, totalSubscriptions, totalNotified, totalCartUpdated);
        log.info("Price drop scan completed: skusProcessed={}, usersNotified={}, cartsUpdated={}",
                totalSubscriptions, totalNotified, totalCartUpdated);
    }

    private int processSkuPriceDrop(String tenantId, String bizType, String skuId,
                                     CartValidateService.ProductValidateResult priceInfo) {
        Set<String> userRefs = cartRedisStorage.getSubscribedUsersForSku(tenantId, bizType, skuId);
        if (userRefs == null || userRefs.isEmpty()) {
            return 0;
        }

        int notifiedCount = 0;
        BigDecimal minDropAmount = resolveMinDropAmount(tenantId, bizType);
        BigDecimal minDropPercent = resolveMinDropPercent(tenantId, bizType);
        int cooldownHours = resolveNotifyCooldownHours(tenantId, bizType);
        boolean notifyEnabled = isNotifyEnabled(tenantId, bizType);

        for (String userRef : userRefs) {
            try {
                String[] parts = userRef.split(":");
                if (parts.length < 3) continue;
                String uTenant = parts[0];
                String uBiz = parts[1];
                String userId = parts[2];

                CartRedisStorage.PriceDropSubscribe sub =
                        cartRedisStorage.getPriceDropSubscribe(uTenant, uBiz, userId, skuId);
                if (sub == null) {
                    cartRedisStorage.cancelPriceDropSubscribe(uTenant, uBiz, userId, skuId);
                    continue;
                }

                CartItem existingItem = getCartItemReadOnly(uTenant, uBiz, userId, skuId);
                BigDecimal oldPrice = existingItem != null ? existingItem.getUnitPrice() : sub.getTargetPrice();
                if (oldPrice == null) oldPrice = priceInfo.getCurrentPrice();

                BigDecimal dropAmount = oldPrice.subtract(priceInfo.getCurrentPrice());
                if (dropAmount.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal dropPercent = dropAmount.divide(oldPrice, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));

                boolean dropByAmount = dropAmount.compareTo(minDropAmount) >= 0;
                boolean dropByPercent = minDropPercent.compareTo(BigDecimal.ZERO) <= 0
                        || dropPercent.compareTo(minDropPercent) >= 0;
                boolean hitTarget = sub.getTargetPrice() != null
                        && priceInfo.getCurrentPrice().compareTo(sub.getTargetPrice()) <= 0;

                if (!(dropByAmount && dropByPercent) && !hitTarget) {
                    continue;
                }

                if (notifyEnabled && !cartRedisStorage.isPriceDropNotified(uTenant, uBiz, userId, skuId)) {
                    cartNotificationService.sendPriceDropNotification(
                            uTenant, uBiz, userId, skuId,
                            StringUtils.defaultIfBlank(priceInfo.getItemName(),
                                    existingItem != null ? existingItem.getItemName() : ""),
                            StringUtils.defaultIfBlank(priceInfo.getItemImage(),
                                    existingItem != null ? existingItem.getItemImage() : ""),
                            oldPrice, priceInfo.getCurrentPrice(), sub.getTargetPrice(),
                            dropAmount, dropPercent,
                            resolveNotifyChannels(tenantId, bizType),
                            resolveNotifyApiUrl(tenantId, bizType),
                            resolveWechatTemplateId(tenantId, bizType),
                            resolveSmsTemplateId(tenantId, bizType));
                    cartRedisStorage.markPriceDropNotified(uTenant, uBiz, userId, skuId, cooldownHours);
                    notifiedCount++;
                }
            } catch (Exception e) {
                log.error("Process user price drop error, userRef={}, skuId={}", userRef, skuId, e);
            }
        }
        return notifiedCount;
    }

    private int autoUpdateCartPrices(String tenantId, String bizType, String skuId,
                                      CartValidateService.ProductValidateResult priceInfo) {
        return 0;
    }

    private int autoUpdateCartPrices(String tenantId, String bizType) {
        if (!isAutoUpdateCartPrice(tenantId, bizType)) {
            return 0;
        }
        return 0;
    }

    private CartItem getCartItemReadOnly(String tenantId, String bizType, String userId, String skuId) {
        try {
            Cart cart = cartRedisStorage.getCartReadOnly(tenantId, bizType, userId);
            if (cart == null || cart.getItems() == null) return null;
            return cart.getItemBySku(skuId);
        } catch (Exception e) {
            return null;
        }
    }

    private List<CartValidateService.ProductValidateResult> batchQueryPrices(
            String tenantId, String bizType, List<String> skuIds) {
        List<ProductValidateDTO> dtoList = skuIds.stream().map(skuId -> {
            ProductValidateDTO dto = new ProductValidateDTO();
            dto.setSkuId(skuId);
            return dto;
        }).collect(Collectors.toList());
        return cartValidateService.remoteValidate(tenantId, bizType, dtoList);
    }

    public Map<String, Object> getPriceDropInfo(String tenantId, String bizType, String userId) {
        Map<String, Object> result = new HashMap<>();
        List<CartRedisStorage.PriceDropSubscribe> subs =
                cartRedisStorage.listPriceDropSubscribes(tenantId, bizType, userId);
        result.put("subscriptions", subs);
        result.put("subscriptionCount", subs.size());

        List<Map<String, Object>> details = new ArrayList<>();
        Cart cart = cartRedisStorage.getCartReadOnly(tenantId, bizType, userId);
        Map<String, CartItem> itemMap = new HashMap<>();
        if (cart != null && cart.getItems() != null) {
            for (CartItem item : cart.getItems()) {
                itemMap.put(item.getSkuId(), item);
            }
        }

        List<String> subscribedSkuIds = subs.stream()
                .map(CartRedisStorage.PriceDropSubscribe::getSkuId).collect(Collectors.toList());
        Map<String, CartValidateService.ProductValidateResult> priceMap = new HashMap<>();
        if (!subscribedSkuIds.isEmpty()) {
            List<CartValidateService.ProductValidateResult> results =
                    batchQueryPrices(tenantId, bizType, subscribedSkuIds);
            for (CartValidateService.ProductValidateResult r : results) {
                priceMap.put(r.getSkuId(), r);
            }
        }

        for (CartRedisStorage.PriceDropSubscribe sub : subs) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("skuId", sub.getSkuId());
            detail.put("targetPrice", sub.getTargetPrice());
            detail.put("createTs", sub.getCreateTs());

            CartItem item = itemMap.get(sub.getSkuId());
            CartValidateService.ProductValidateResult pv = priceMap.get(sub.getSkuId());
            BigDecimal currentPrice = pv != null ? pv.getCurrentPrice()
                    : (item != null ? item.getUnitPrice() : null);
            detail.put("itemName", pv != null ? pv.getItemName()
                    : (item != null ? item.getItemName() : null));
            detail.put("itemImage", pv != null ? pv.getItemImage()
                    : (item != null ? item.getItemImage() : null));
            detail.put("currentPrice", currentPrice);

            if (currentPrice != null && sub.getTargetPrice() != null) {
                BigDecimal gap = currentPrice.subtract(sub.getTargetPrice());
                detail.put("priceGap", gap);
                detail.put("reachedTarget", gap.compareTo(BigDecimal.ZERO) <= 0);
                if (gap.compareTo(BigDecimal.ZERO) > 0 && sub.getTargetPrice().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal gapPercent = gap.divide(sub.getTargetPrice(), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));
                    detail.put("gapPercent", gapPercent);
                }
            }

            detail.put("notified", cartRedisStorage.isPriceDropNotified(tenantId, bizType, userId, sub.getSkuId()));
            details.add(detail);
        }
        result.put("details", details);

        result.put("enabled", isPriceDropEnabled(tenantId, bizType));
        result.put("notifyEnabled", isNotifyEnabled(tenantId, bizType));
        result.put("subscriptionExpireDays", cartRedisStorage.resolveSubscriptionExpireDays(tenantId, bizType));
        result.put("minDropPercent", resolveMinDropPercent(tenantId, bizType));
        result.put("minDropAmount", resolveMinDropAmount(tenantId, bizType));

        return result;
    }

    public boolean subscribe(String tenantId, String bizType, String userId,
                              String skuId, BigDecimal targetPrice) {
        if (!isPriceDropEnabled(tenantId, bizType)) {
            throw new IllegalStateException("降价提醒功能未启用");
        }
        if (StringUtils.isBlank(skuId)) {
            throw new IllegalArgumentException("SKU ID 不能为空");
        }
        cartRedisStorage.setPriceDropSubscribe(tenantId, bizType, userId, skuId,
                targetPrice, System.currentTimeMillis());
        return true;
    }

    public boolean unsubscribe(String tenantId, String bizType, String userId, String skuId) {
        cartRedisStorage.cancelPriceDropSubscribe(tenantId, bizType, userId, skuId);
        return true;
    }

    public boolean batchUnsubscribe(String tenantId, String bizType, String userId, List<String> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            List<CartRedisStorage.PriceDropSubscribe> subs =
                    cartRedisStorage.listPriceDropSubscribes(tenantId, bizType, userId);
            for (CartRedisStorage.PriceDropSubscribe sub : subs) {
                cartRedisStorage.cancelPriceDropSubscribe(tenantId, bizType, userId, sub.getSkuId());
            }
        } else {
            for (String skuId : skuIds) {
                cartRedisStorage.cancelPriceDropSubscribe(tenantId, bizType, userId, skuId);
            }
        }
        return true;
    }

    public Map<String, Object> getStatistics(String tenantId, String bizType, String date) {
        Map<String, Object> result = new HashMap<>();
        if (StringUtils.isBlank(date)) {
            date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        }
        String statKey = RedisKeyConstant.buildPriceDropStatKey(date);
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(statKey);
        for (Map.Entry<Object, Object> e : entries.entrySet()) {
            try {
                result.put(String.valueOf(e.getKey()), Long.parseLong(String.valueOf(e.getValue())));
            } catch (Exception ignored) {
                result.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if (result.isEmpty()) {
            result.put("skusProcessed", 0);
            result.put("usersNotified", 0);
            result.put("cartsUpdated", 0);
        }
        long totalSubSku = 0;
        long totalSubUser = 0;
        try {
            Set<String> skuKeys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConstant.buildPriceDropSkuKey(tenantId, bizType, "*"), 1000);
            Set<String> userKeys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConstant.buildPriceDropUserKey(tenantId, bizType, "*"), 1000);
            totalSubSku = skuKeys.size();
            totalSubUser = userKeys.size();
        } catch (Exception ignored) {
        }
        result.put("totalSubscribedSkuCount", totalSubSku);
        result.put("totalSubscribedUserCount", totalSubUser);
        return result;
    }

    private void recordStatistics(String tenantId, String bizType,
                                   int skusProcessed, int usersNotified, int cartsUpdated) {
        try {
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String statKey = RedisKeyConstant.buildPriceDropStatKey(date);
            stringRedisTemplate.opsForHash().increment(statKey, "skusProcessed", skusProcessed);
            stringRedisTemplate.opsForHash().increment(statKey, "usersNotified", usersNotified);
            stringRedisTemplate.opsForHash().increment(statKey, "cartsUpdated", cartsUpdated);
            stringRedisTemplate.expire(statKey, Duration.ofDays(30));
        } catch (Exception ignored) {
        }
    }

    private BizConfigEntity getBizConfig(String tenantId, String bizType) {
        try {
            return bizConfigService.getConfig(tenantId, bizType);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPriceDropEnabled(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && cfg.getPriceDropEnable() != null) {
            return cfg.getPriceDropEnable() == 1;
        }
        return Boolean.TRUE.equals(cartHubProperties.getPriceDrop().getEnable());
    }

    private boolean isNotifyEnabled(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && cfg.getPriceDropNotifyEnable() != null) {
            return cfg.getPriceDropNotifyEnable() == 1;
        }
        return Boolean.TRUE.equals(cartHubProperties.getPriceDrop().getEnableNotification());
    }

    private boolean isAutoUpdateCartPrice(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && cfg.getPriceDropAutoUpdateCart() != null) {
            return cfg.getPriceDropAutoUpdateCart() == 1;
        }
        return Boolean.TRUE.equals(cartHubProperties.getPriceDrop().getAutoUpdateCartPrice());
    }

    private int resolveBatchSize(String tenantId, String bizType) {
        if (cartHubProperties.getPriceDrop() != null
                && cartHubProperties.getPriceDrop().getBatchSize() != null) {
            return cartHubProperties.getPriceDrop().getBatchSize();
        }
        return 200;
    }

    private BigDecimal resolveMinDropPercent(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && cfg.getPriceDropMinPercent() != null) {
            return BigDecimal.valueOf(cfg.getPriceDropMinPercent());
        }
        if (cartHubProperties.getPriceDrop() != null
                && cartHubProperties.getPriceDrop().getMinDropPercent() != null) {
            return cartHubProperties.getPriceDrop().getMinDropPercent();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal resolveMinDropAmount(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && cfg.getPriceDropMinAmount() != null) {
            return cfg.getPriceDropMinAmount();
        }
        if (cartHubProperties.getPriceDrop() != null
                && cartHubProperties.getPriceDrop().getMinDropAmount() != null) {
            return cartHubProperties.getPriceDrop().getMinDropAmount();
        }
        return BigDecimal.ONE;
    }

    private int resolveNotifyCooldownHours(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && cfg.getPriceDropNotifyCooldownHours() != null
                && cfg.getPriceDropNotifyCooldownHours() > 0) {
            return cfg.getPriceDropNotifyCooldownHours();
        }
        if (cartHubProperties.getPriceDrop() != null
                && cartHubProperties.getPriceDrop().getNotifyCooldownHours() != null) {
            return cartHubProperties.getPriceDrop().getNotifyCooldownHours();
        }
        return 24;
    }

    private String resolveNotifyChannels(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && StringUtils.isNotBlank(cfg.getPriceDropNotifyChannels())) {
            return cfg.getPriceDropNotifyChannels();
        }
        return cartHubProperties.getPriceDrop() != null
                ? cartHubProperties.getPriceDrop().getNotifyChannels()
                : "wechat,sms";
    }

    private String resolveNotifyApiUrl(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && StringUtils.isNotBlank(cfg.getPriceDropNotifyApiUrl())) {
            return cfg.getPriceDropNotifyApiUrl();
        }
        return cartHubProperties.getPriceDrop() != null
                ? cartHubProperties.getPriceDrop().getNotifyApiUrl()
                : (cartHubProperties.getCleanup() != null
                        ? cartHubProperties.getCleanup().getNotifyApiUrl() : null);
    }

    private String resolveWechatTemplateId(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && StringUtils.isNotBlank(cfg.getPriceDropWechatTemplateId())) {
            return cfg.getPriceDropWechatTemplateId();
        }
        return cartHubProperties.getPriceDrop() != null
                ? cartHubProperties.getPriceDrop().getWechatTemplateId()
                : (cartHubProperties.getCleanup() != null
                        ? cartHubProperties.getCleanup().getWechatTemplateId() : null);
    }

    private String resolveSmsTemplateId(String tenantId, String bizType) {
        BizConfigEntity cfg = getBizConfig(tenantId, bizType);
        if (cfg != null && StringUtils.isNotBlank(cfg.getPriceDropSmsTemplateId())) {
            return cfg.getPriceDropSmsTemplateId();
        }
        return cartHubProperties.getPriceDrop() != null
                ? cartHubProperties.getPriceDrop().getSmsTemplateId()
                : (cartHubProperties.getCleanup() != null
                        ? cartHubProperties.getCleanup().getSmsTemplateId() : null);
    }

}
