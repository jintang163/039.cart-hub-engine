package com.carhub.storage;

import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.entity.BizConfigEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartDiscount;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.model.DiscountDetail;
import com.carhub.domain.model.GiftItem;
import com.carhub.service.BizConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBatch;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartRedisStorage {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final CartHubProperties cartHubProperties;
    private final BizConfigService bizConfigService;

    @Resource(name = "redisTemplate")
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    public boolean addItem(String tenantId, String bizType, String userId, CartItem item) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (item == null || StringUtils.isBlank(item.getSkuId())) {
            return false;
        }
        item.recalculate();
        if (item.getAddTime() == null) {
            item.setAddTime(System.currentTimeMillis());
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        String oldItemJson = cartMap.putIfAbsent(item.getSkuId(), JsonUtil.toJson(item));
        boolean added = oldItemJson == null;
        if (added) {
            setCartExpire(tenantId, bizType, cartKey);
            incrementVersion(tenantId, bizType, cartKey);
            updateLastAccessTime(tenantId, bizType, userId);
        }
        if (log.isDebugEnabled()) {
            log.debug("addItem result: tenantId={}, bizType={}, userId={}, skuId={}, added={}",
                    tenantId, bizType, userId, item.getSkuId(), added);
        }
        return added;
    }

    public int batchAddItems(String tenantId, String bizType, String userId, List<CartItem> items, boolean overwrite) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (items == null || items.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        RBatch batch = redissonClient.createBatch();
        RMap<String, String> batchMap = batch.getMap(cartKey);
        int count = 0;
        for (CartItem item : items) {
            if (item == null || StringUtils.isBlank(item.getSkuId())) {
                continue;
            }
            item.recalculate();
            if (item.getAddTime() == null) {
                item.setAddTime(now);
            }
            if (overwrite) {
                batchMap.fastPutAsync(item.getSkuId(), JsonUtil.toJson(item));
            } else {
                batchMap.putIfAbsentAsync(item.getSkuId(), JsonUtil.toJson(item));
            }
            count++;
        }
        if (count > 0) {
            List<?> results = batch.execute().getResponses();
            int added = 0;
            for (int i = 0; i < results.size() && i < count; i++) {
                Object result = results.get(i);
                if (overwrite) {
                    added++;
                } else {
                    if (result == null) {
                        added++;
                    }
                }
            }
            if (added > 0) {
                setCartExpire(tenantId, bizType, cartKey);
                incrementVersion(tenantId, bizType, cartKey);
                updateLastAccessTime(tenantId, bizType, userId);
            }
            log.info("batchAddItems: tenantId={}, bizType={}, userId={}, total={}, added={}, overwrite={}",
                    tenantId, bizType, userId, count, added, overwrite);
            return added;
        }
        return 0;
    }

    public boolean updateItem(String tenantId, String bizType, String userId, CartItem item) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (item == null || StringUtils.isBlank(item.getSkuId())) {
            return false;
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        String oldJson = cartMap.get(item.getSkuId());
        if (StringUtils.isBlank(oldJson)) {
            return false;
        }
        CartItem oldItem = JsonUtil.fromJson(oldJson, CartItem.class);
        if (item.getQuantity() != null) {
            oldItem.setQuantity(item.getQuantity());
        }
        if (item.getUnitPrice() != null) {
            oldItem.setUnitPrice(item.getUnitPrice());
        }
        if (item.getSelected() != null) {
            oldItem.setSelected(item.getSelected());
        }
        if (StringUtils.isNotBlank(item.getItemName())) {
            oldItem.setItemName(item.getItemName());
        }
        if (StringUtils.isNotBlank(item.getItemImage())) {
            oldItem.setItemImage(item.getItemImage());
        }
        if (item.getItemSpec() != null && !item.getItemSpec().isEmpty()) {
            oldItem.setItemSpec(item.getItemSpec());
        }
        if (item.getOnShelf() != null) {
            oldItem.setOnShelf(item.getOnShelf());
        }
        if (item.getPriceChanged() != null) {
            oldItem.setPriceChanged(item.getPriceChanged());
        }
        if (item.getOldPrice() != null) {
            oldItem.setOldPrice(item.getOldPrice());
        }
        if (item.getInvalidMessage() != null) {
            oldItem.setInvalidMessage(item.getInvalidMessage());
        }
        if (item.getStock() != null) {
            oldItem.setStock(item.getStock());
        }
        if (item.getExtInfo() != null && !item.getExtInfo().isEmpty()) {
            if (oldItem.getExtInfo() == null) {
                oldItem.setExtInfo(new HashMap<>());
            }
            oldItem.getExtInfo().putAll(item.getExtInfo());
        }
        if (StringUtils.isNotBlank(item.getRemark())) {
            oldItem.setRemark(item.getRemark());
        } else if (item.getRemark() != null) {
            oldItem.setRemark(null);
        }
        if (item.getSortWeight() != null) {
            oldItem.setSortWeight(item.getSortWeight());
        }
        oldItem.recalculate();
        cartMap.fastPut(item.getSkuId(), JsonUtil.toJson(oldItem));
        setCartExpire(tenantId, bizType, cartKey);
        incrementVersion(tenantId, bizType, cartKey);
        updateLastAccessTime(tenantId, bizType, userId);
        return true;
    }

    public boolean mergeUpdateItem(String tenantId, String bizType, String userId, CartItem newItem, boolean overwrite) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (newItem == null || StringUtils.isBlank(newItem.getSkuId())) {
            return false;
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        String oldJson = cartMap.get(newItem.getSkuId());
        if (StringUtils.isBlank(oldJson)) {
            newItem.recalculate();
            if (newItem.getAddTime() == null) {
                newItem.setAddTime(System.currentTimeMillis());
            }
            cartMap.fastPut(newItem.getSkuId(), JsonUtil.toJson(newItem));
            setCartExpire(tenantId, bizType, cartKey);
            incrementVersion(tenantId, bizType, cartKey);
            return true;
        }
        CartItem oldItem = JsonUtil.fromJson(oldJson, CartItem.class);
        if (overwrite) {
            newItem.setAddTime(oldItem.getAddTime());
            newItem.recalculate();
            cartMap.fastPut(newItem.getSkuId(), JsonUtil.toJson(newItem));
        } else {
            int mergedQty = (oldItem.getQuantity() == null ? 0 : oldItem.getQuantity())
                    + (newItem.getQuantity() == null ? 1 : newItem.getQuantity());
            oldItem.setQuantity(mergedQty);
            oldItem.recalculate();
            cartMap.fastPut(newItem.getSkuId(), JsonUtil.toJson(oldItem));
        }
        setCartExpire(tenantId, bizType, cartKey);
        incrementVersion(tenantId, bizType, cartKey);
        updateLastAccessTime(tenantId, bizType, userId);
        return true;
    }

    public boolean removeItem(String tenantId, String bizType, String userId, String skuId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (StringUtils.isBlank(skuId)) {
            return false;
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        Object removed = cartMap.remove(skuId);
        if (removed != null) {
            incrementVersion(tenantId, bizType, cartKey);
            updateLastAccessTime(tenantId, bizType, userId);
            return true;
        }
        return false;
    }

    public long batchRemove(String tenantId, String bizType, String userId, List<String> skuIds) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (skuIds == null || skuIds.isEmpty()) {
            return 0;
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        RBatch batch = redissonClient.createBatch();
        RMap<String, String> batchMap = batch.getMap(cartKey);
        for (String skuId : skuIds) {
            batchMap.removeAsync(skuId);
        }
        List<?> result = batch.execute().getResponses();
        long count = result.stream().filter(Objects::nonNull).count();
        if (count > 0) {
            incrementVersion(tenantId, bizType, cartKey);
            updateLastAccessTime(tenantId, bizType, userId);
        }
        return count;
    }

    public boolean clearCart(String tenantId, String bizType, String userId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        cartMap.delete();
        deleteVersion(cartKey);
        clearCartMeta(tenantId, bizType, userId);
        clearLastAccessAndRemind(tenantId, bizType, userId);
        return true;
    }

    public CartItem getItem(String tenantId, String bizType, String userId, String skuId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (StringUtils.isBlank(skuId)) {
            return null;
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        String json = cartMap.get(skuId);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JsonUtil.fromJson(json, CartItem.class);
    }

    public List<CartItem> getItems(String tenantId, String bizType, String userId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        Map<String, String> all = cartMap.readAllMap();
        if (all == null || all.isEmpty()) {
            return new ArrayList<>();
        }
        List<CartItem> items = all.values().stream()
                .map(json -> JsonUtil.fromJson(json, CartItem.class))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        items.sort((a, b) -> {
            Integer wa = a.getSortWeight();
            Integer wb = b.getSortWeight();
            if (wa != null && wb != null) {
                return Integer.compare(wa, wb);
            }
            if (wa != null) return -1;
            if (wb != null) return 1;
            Long ta = a.getAddTime();
            Long tb = b.getAddTime();
            if (ta != null && tb != null) {
                return Long.compare(tb, ta);
            }
            return 0;
        });
        return items;
    }

    public List<CartItem> getItemsBySkus(String tenantId, String bizType, String userId, List<String> skuIds) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (skuIds == null || skuIds.isEmpty()) {
            return new ArrayList<>();
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        Map<String, String> map = cartMap.getAll(new HashSet<>(skuIds));
        if (map == null || map.isEmpty()) {
            return new ArrayList<>();
        }
        return map.values().stream()
                .map(json -> JsonUtil.fromJson(json, CartItem.class))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Cart getCart(String tenantId, String bizType, String userId) {
        Cart cart = buildCartFromStorage(tenantId, bizType, userId);
        updateLastAccessTime(tenantId, bizType, userId);
        return cart;
    }

    public Cart getCartReadOnly(String tenantId, String bizType, String userId) {
        return buildCartFromStorage(tenantId, bizType, userId);
    }

    private Cart buildCartFromStorage(String tenantId, String bizType, String userId) {
        List<CartItem> items = getItems(tenantId, bizType, userId);
        Cart cart = Cart.builder()
                .tenantId(tenantId)
                .bizType(bizType)
                .userId(userId)
                .items(items)
                .version(getVersion(tenantId, bizType, userId))
                .build();

        CartMeta meta = getCartMeta(tenantId, bizType, userId);
        if (meta != null) {
            cart.setSelectedCouponId(meta.getSelectedCouponId());
            cart.setSelectedPromotionIds(meta.getSelectedPromotionIds());
            cart.setCouponCode(meta.getCouponCode());
            cart.setDiscounts(meta.getDiscounts());
            cart.setDiscountDetails(meta.getDiscountDetails());
            cart.setGifts(meta.getGifts());
            cart.setDiscountAmount(meta.getDiscountAmount());
            cart.setPayAmount(meta.getPayAmount());
            cart.setDiscountCalculated(meta.getDiscountCalculated());
            cart.setDiscountCalculateTime(meta.getDiscountCalculateTime());
        }

        Long lastAccessTime = getLastAccessTime(tenantId, bizType, userId);
        cart.setLastAccessTime(lastAccessTime);
        int retentionDays = resolveItemRetentionDays(tenantId, bizType);
        if (lastAccessTime != null && retentionDays > 0) {
            long expireMs = lastAccessTime + retentionDays * 86400_000L;
            cart.setExpireTime(expireMs);
        }

        cart.recalculate();
        return cart;
    }

    public int resolveItemRetentionDays(String tenantId, String bizType) {
        try {
            BizConfigEntity cfg = bizConfigService.getConfig(tenantId, bizType);
            if (cfg != null && cfg.getItemRetentionDays() != null && cfg.getItemRetentionDays() > 0) {
                return cfg.getItemRetentionDays();
            }
        } catch (Exception e) {
            log.warn("Resolve itemRetentionDays from biz config failed, fallback to default", e);
        }
        if (cartHubProperties.getCleanup() != null && cartHubProperties.getCleanup().getItemRetentionDays() != null) {
            return cartHubProperties.getCleanup().getItemRetentionDays();
        }
        return 30;
    }

    public int resolveRemindBeforeDays(String tenantId, String bizType) {
        try {
            BizConfigEntity cfg = bizConfigService.getConfig(tenantId, bizType);
            if (cfg != null && cfg.getRemindBeforeDays() != null && cfg.getRemindBeforeDays() > 0) {
                return cfg.getRemindBeforeDays();
            }
        } catch (Exception e) {
            log.warn("Resolve remindBeforeDays from biz config failed, fallback to default", e);
        }
        if (cartHubProperties.getCleanup() != null && cartHubProperties.getCleanup().getRemindBeforeDays() != null) {
            return cartHubProperties.getCleanup().getRemindBeforeDays();
        }
        return 3;
    }

    public int resolveCartExpireSeconds(String tenantId, String bizType) {
        int retentionDays = resolveItemRetentionDays(tenantId, bizType);
        int bufferDays = 2;
        return (retentionDays + bufferDays) * 86400;
    }

    public void updateLastAccessTime(String tenantId, String bizType, String userId) {
        updateLastAccessTime(tenantId, bizType, userId, null);
    }

    public void updateLastAccessTime(String tenantId, String bizType, String userId, Long candidateTs) {
        String key = RedisKeyConstant.buildLastAccessKey(tenantId, bizType, userId);
        int expireSeconds = resolveCartExpireSeconds(tenantId, bizType);
        long now = System.currentTimeMillis();
        long finalTs = now;
        if (candidateTs != null && candidateTs > now) {
            finalTs = candidateTs;
        } else if (candidateTs != null) {
            String current = stringRedisTemplate.opsForValue().get(key);
            long existingTs = 0;
            if (StringUtils.isNotBlank(current)) {
                try { existingTs = Long.parseLong(current); } catch (NumberFormatException ignored) {}
            }
            finalTs = Math.max(Math.max(now, candidateTs), existingTs);
        }
        stringRedisTemplate.opsForValue().set(key, String.valueOf(finalTs),
                Duration.ofSeconds(expireSeconds));
    }

    public Long getLastAccessTime(String tenantId, String bizType, String userId) {
        String key = RedisKeyConstant.buildLastAccessKey(tenantId, bizType, userId);
        String value = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setExpireReminded(String tenantId, String bizType, String userId, int days) {
        String key = RedisKeyConstant.buildExpireRemindKey(tenantId, bizType, userId);
        stringRedisTemplate.opsForValue().set(key, String.valueOf(days),
                Duration.ofDays(days + 1));
    }

    public boolean hasExpireReminded(String tenantId, String bizType, String userId) {
        String key = RedisKeyConstant.buildExpireRemindKey(tenantId, bizType, userId);
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    public List<String> scanExpiredCarts(String tenantId, String bizType, long expireBeforeTs, int limit) {
        List<String> result = new ArrayList<>();
        String pattern = RedisKeyConstant.buildLastAccessKey(tenantId, bizType, "*");
        var keys = redissonClient.getKeys().getKeysByPattern(pattern, 100);
        int count = 0;
        for (String key : keys) {
            if (count >= limit) break;
            String value = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.isNotBlank(value)) {
                try {
                    long lastAccess = Long.parseLong(value);
                    if (lastAccess <= expireBeforeTs) {
                        String userId = extractUserIdFromAccessKey(key, tenantId, bizType);
                        if (userId != null) {
                            result.add(userId);
                            count++;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    public List<String> scanExpiringCarts(String tenantId, String bizType, long remindBeforeTs,
                                          long expireBeforeTs, int limit) {
        List<String> result = new ArrayList<>();
        String pattern = RedisKeyConstant.buildLastAccessKey(tenantId, bizType, "*");
        var keys = redissonClient.getKeys().getKeysByPattern(pattern, 100);
        int count = 0;
        for (String key : keys) {
            if (count >= limit) break;
            String value = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.isNotBlank(value)) {
                try {
                    long lastAccess = Long.parseLong(value);
                    if (lastAccess <= remindBeforeTs && lastAccess > expireBeforeTs) {
                        String userId = extractUserIdFromAccessKey(key, tenantId, bizType);
                        if (userId != null) {
                            result.add(userId);
                            count++;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return result;
    }

    private String extractUserIdFromAccessKey(String key, String tenantId, String bizType) {
        try {
            String prefix = RedisKeyConstant.buildLastAccessKey(tenantId, bizType, "");
            prefix = prefix.substring(0, prefix.length() - 2);
            return key.substring(prefix.length());
        } catch (Exception e) {
            return null;
        }
    }

    public void clearLastAccessAndRemind(String tenantId, String bizType, String userId) {
        stringRedisTemplate.delete(RedisKeyConstant.buildLastAccessKey(tenantId, bizType, userId));
        stringRedisTemplate.delete(RedisKeyConstant.buildExpireRemindKey(tenantId, bizType, userId));
    }

    public Integer getItemCount(String tenantId, String bizType, String userId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        return cartMap.size();
    }

    public boolean existsItem(String tenantId, String bizType, String userId, String skuId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (StringUtils.isBlank(skuId)) {
            return false;
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        return cartMap.containsKey(skuId);
    }

    public Long incrementQuantity(String tenantId, String bizType, String userId, String skuId, int delta) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        if (StringUtils.isBlank(skuId)) {
            return null;
        }
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        String json = cartMap.get(skuId);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        CartItem item = JsonUtil.fromJson(json, CartItem.class);
        int newQty = (item.getQuantity() == null ? 0 : item.getQuantity()) + delta;
        if (newQty <= 0) {
            cartMap.remove(skuId);
            incrementVersion(tenantId, bizType, cartKey);
            return 0L;
        }
        item.setQuantity(newQty);
        item.recalculate();
        cartMap.fastPut(skuId, JsonUtil.toJson(item));
        setCartExpire(tenantId, bizType, cartKey);
        incrementVersion(tenantId, bizType, cartKey);
        updateLastAccessTime(tenantId, bizType, userId);
        return (long) newQty;
    }

    private void setCartExpire(String tenantId, String bizType, String cartKey) {
        int expireSeconds = resolveCartExpireSeconds(tenantId, bizType);
        redissonClient.getMap(cartKey).expire(Duration.ofSeconds(expireSeconds));
    }

    private String getVersionKey(String cartKey) {
        return cartKey + ":version";
    }

    private Long getVersion(String tenantId, String bizType, String userId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        String verStr = stringRedisTemplate.opsForValue().get(getVersionKey(cartKey));
        return verStr == null ? 0L : Long.parseLong(verStr);
    }

    private void incrementVersion(String tenantId, String bizType, String cartKey) {
        stringRedisTemplate.opsForValue().increment(getVersionKey(cartKey));
        int expireSeconds = resolveCartExpireSeconds(tenantId, bizType);
        stringRedisTemplate.expire(getVersionKey(cartKey), Duration.ofSeconds(expireSeconds));
    }

    private void deleteVersion(String cartKey) {
        stringRedisTemplate.delete(getVersionKey(cartKey));
    }

    public Set<String> searchUsersWithSku(String tenantId, String bizType, String skuId, int limit) {
        Set<String> result = new HashSet<>();
        String pattern = RedisKeyConstant.buildCartKey(tenantId, bizType, "*");
        var keys = redissonClient.getKeys().getKeysByPattern(pattern, 100);
        int count = 0;
        for (String key : keys) {
            if (count >= limit) break;
            RMap<String, String> cartMap = redissonClient.getMap(key);
            if (cartMap.containsKey(skuId)) {
                String userId = extractUserIdFromKey(key, tenantId, bizType);
                if (userId != null) {
                    result.add(userId);
                    count++;
                }
            }
        }
        return result;
    }

    private String extractUserIdFromKey(String key, String tenantId, String bizType) {
        try {
            String prefix = RedisKeyConstant.buildCartKey(tenantId, bizType, "");
            prefix = prefix.substring(0, prefix.length() - 2);
            return key.substring(prefix.length());
        } catch (Exception e) {
            return null;
        }
    }

    public void saveCartMeta(String tenantId, String bizType, String userId, Cart cart) {
        String metaKey = RedisKeyConstant.buildCartMetaKey(tenantId, bizType, userId);
        CartMeta meta = CartMeta.builder()
                .selectedCouponId(cart.getSelectedCouponId())
                .selectedPromotionIds(cart.getSelectedPromotionIds())
                .couponCode(cart.getCouponCode())
                .discounts(cart.getDiscounts())
                .discountDetails(cart.getDiscountDetails())
                .gifts(cart.getGifts())
                .discountAmount(cart.getDiscountAmount())
                .payAmount(cart.getPayAmount())
                .discountCalculated(cart.getDiscountCalculated())
                .discountCalculateTime(cart.getDiscountCalculateTime())
                .build();
        int expireSeconds = resolveCartExpireSeconds(tenantId, bizType);
        stringRedisTemplate.opsForValue().set(metaKey, JsonUtil.toJson(meta),
                Duration.ofSeconds(expireSeconds));
    }

    public CartMeta getCartMeta(String tenantId, String bizType, String userId) {
        String metaKey = RedisKeyConstant.buildCartMetaKey(tenantId, bizType, userId);
        String json = stringRedisTemplate.opsForValue().get(metaKey);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JsonUtil.fromJson(json, CartMeta.class);
        } catch (Exception e) {
            log.warn("Parse cart meta failed: {}", e.getMessage());
            return null;
        }
    }

    public void clearCartMeta(String tenantId, String bizType, String userId) {
        String metaKey = RedisKeyConstant.buildCartMetaKey(tenantId, bizType, userId);
        stringRedisTemplate.delete(metaKey);
    }

    public int batchUpdateSort(String tenantId, String bizType, String userId, Map<String, Integer> sortMap) {
        if (sortMap == null || sortMap.isEmpty()) {
            return 0;
        }
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        int updated = 0;
        for (Map.Entry<String, Integer> entry : sortMap.entrySet()) {
            String skuId = entry.getKey();
            Integer sortWeight = entry.getValue();
            String oldJson = cartMap.get(skuId);
            if (StringUtils.isBlank(oldJson)) {
                continue;
            }
            CartItem item = JsonUtil.fromJson(oldJson, CartItem.class);
            if (item == null) continue;
            item.setSortWeight(sortWeight);
            cartMap.fastPut(skuId, JsonUtil.toJson(item));
            updated++;
        }
        if (updated > 0) {
            setCartExpire(tenantId, bizType, cartKey);
            incrementVersion(tenantId, bizType, cartKey);
        }
        return updated;
    }

    public boolean setPriceDropSubscribe(String tenantId, String bizType, String userId, String skuId,
                                          BigDecimal targetPrice, Long createTs) {
        String userKey = RedisKeyConstant.buildPriceDropUserKey(tenantId, bizType, userId);
        String skuKey = RedisKeyConstant.buildPriceDropSkuKey(tenantId, bizType, skuId);
        int expireDays = resolveSubscriptionExpireDays(tenantId, bizType);
        String json = JsonUtil.toJson(PriceDropSubscribe.builder()
                .skuId(skuId)
                .targetPrice(targetPrice)
                .createTs(createTs)
                .build());
        stringRedisTemplate.opsForHash().put(userKey, skuId, json);
        stringRedisTemplate.expire(userKey, Duration.ofDays(expireDays));
        String userRef = tenantId + ":" + bizType + ":" + userId;
        stringRedisTemplate.opsForSet().add(skuKey, userRef);
        stringRedisTemplate.expire(skuKey, Duration.ofDays(expireDays));
        return true;
    }

    public boolean cancelPriceDropSubscribe(String tenantId, String bizType, String userId, String skuId) {
        String userKey = RedisKeyConstant.buildPriceDropUserKey(tenantId, bizType, userId);
        String skuKey = RedisKeyConstant.buildPriceDropSkuKey(tenantId, bizType, skuId);
        stringRedisTemplate.opsForHash().delete(userKey, skuId);
        String userRef = tenantId + ":" + bizType + ":" + userId;
        stringRedisTemplate.opsForSet().remove(skuKey, userRef);
        stringRedisTemplate.delete(RedisKeyConstant.buildPriceDropNotifyKey(tenantId, bizType, userId, skuId));
        return true;
    }

    public List<PriceDropSubscribe> listPriceDropSubscribes(String tenantId, String bizType, String userId) {
        String userKey = RedisKeyConstant.buildPriceDropUserKey(tenantId, bizType, userId);
        List<Object> values = stringRedisTemplate.opsForHash().values(userKey);
        List<PriceDropSubscribe> result = new ArrayList<>();
        for (Object v : values) {
            try {
                PriceDropSubscribe sub = JsonUtil.fromJson(String.valueOf(v), PriceDropSubscribe.class);
                if (sub != null) result.add(sub);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public boolean isPriceDropNotified(String tenantId, String bizType, String userId, String skuId) {
        String key = RedisKeyConstant.buildPriceDropNotifyKey(tenantId, bizType, userId, skuId);
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    public void markPriceDropNotified(String tenantId, String bizType, String userId, String skuId, int cooldownHours) {
        String key = RedisKeyConstant.buildPriceDropNotifyKey(tenantId, bizType, userId, skuId);
        stringRedisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
                Duration.ofHours(Math.max(cooldownHours, 1)));
    }

    public int resolveSubscriptionExpireDays(String tenantId, String bizType) {
        try {
            BizConfigEntity cfg = bizConfigService.getConfig(tenantId, bizType);
            if (cfg != null && cfg.getPriceDropSubscriptionDays() != null && cfg.getPriceDropSubscriptionDays() > 0) {
                return cfg.getPriceDropSubscriptionDays();
            }
        } catch (Exception e) {
            log.warn("Resolve subscription expire days from biz config failed, fallback to default", e);
        }
        if (cartHubProperties.getPriceDrop() != null && cartHubProperties.getPriceDrop().getSubscriptionExpireDays() != null) {
            return cartHubProperties.getPriceDrop().getSubscriptionExpireDays();
        }
        return 180;
    }

    public List<String> scanAllSubscribedSkuIds(String tenantId, String bizType, int limit) {
        String pattern = RedisKeyConstant.buildPriceDropSkuKey(tenantId, bizType, "*");
        List<String> result = new ArrayList<>();
        int count = 0;
        for (String key : redissonClient.getKeys().getKeysByPattern(pattern, 100)) {
            if (count >= limit) break;
            String prefix = RedisKeyConstant.buildPriceDropSkuKey(tenantId, bizType, "");
            prefix = prefix.substring(0, prefix.length() - 2);
            if (key.length() > prefix.length()) {
                result.add(key.substring(prefix.length()));
                count++;
            }
        }
        return result;
    }

    public Set<String> getSubscribedUsersForSku(String tenantId, String bizType, String skuId) {
        String skuKey = RedisKeyConstant.buildPriceDropSkuKey(tenantId, bizType, skuId);
        Set<String> refs = stringRedisTemplate.opsForSet().members(skuKey);
        return refs == null ? new HashSet<>() : refs;
    }

    public PriceDropSubscribe getPriceDropSubscribe(String tenantId, String bizType, String userId, String skuId) {
        String userKey = RedisKeyConstant.buildPriceDropUserKey(tenantId, bizType, userId);
        Object val = stringRedisTemplate.opsForHash().get(userKey, skuId);
        if (val == null) return null;
        try {
            return JsonUtil.fromJson(String.valueOf(val), PriceDropSubscribe.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceDropSubscribe implements Serializable {
        private static final long serialVersionUID = 1L;
        private String skuId;
        private BigDecimal targetPrice;
        private Long createTs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartMeta implements Serializable {
        private static final long serialVersionUID = 1L;
        private String selectedCouponId;
        private List<String> selectedPromotionIds;
        private String couponCode;
        private List<CartDiscount> discounts;
        private List<DiscountDetail> discountDetails;
        private List<GiftItem> gifts;
        private BigDecimal discountAmount;
        private BigDecimal payAmount;
        private Boolean discountCalculated;
        private Long discountCalculateTime;
    }

}
