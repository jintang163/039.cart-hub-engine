package com.carhub.storage;

import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartDiscount;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.model.DiscountDetail;
import com.carhub.domain.model.GiftItem;
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
            setCartExpire(cartKey);
            incrementVersion(cartKey);
        }
        if (log.isDebugEnabled()) {
            log.debug("addItem result: tenantId={}, bizType={}, userId={}, skuId={}, added={}",
                    tenantId, bizType, userId, item.getSkuId(), added);
        }
        return added;
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
        setCartExpire(cartKey);
        incrementVersion(cartKey);
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
            setCartExpire(cartKey);
            incrementVersion(cartKey);
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
        setCartExpire(cartKey);
        incrementVersion(cartKey);
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
            incrementVersion(cartKey);
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
            incrementVersion(cartKey);
        }
        return count;
    }

    public boolean clearCart(String tenantId, String bizType, String userId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        RMap<String, String> cartMap = redissonClient.getMap(cartKey);
        cartMap.delete();
        deleteVersion(cartKey);
        clearCartMeta(tenantId, bizType, userId);
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

        cart.recalculate();
        return cart;
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
            incrementVersion(cartKey);
            return 0L;
        }
        item.setQuantity(newQty);
        item.recalculate();
        cartMap.fastPut(skuId, JsonUtil.toJson(item));
        setCartExpire(cartKey);
        incrementVersion(cartKey);
        return (long) newQty;
    }

    private void setCartExpire(String cartKey) {
        Integer expire = cartHubProperties.getRedis().getCartExpireSeconds();
        if (expire != null && expire > 0) {
            redissonClient.getMap(cartKey).expire(Duration.ofSeconds(expire));
        }
    }

    private String getVersionKey(String cartKey) {
        return cartKey + ":version";
    }

    private Long getVersion(String tenantId, String bizType, String userId) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        String verStr = stringRedisTemplate.opsForValue().get(getVersionKey(cartKey));
        return verStr == null ? 0L : Long.parseLong(verStr);
    }

    private void incrementVersion(String cartKey) {
        stringRedisTemplate.opsForValue().increment(getVersionKey(cartKey));
        stringRedisTemplate.expire(getVersionKey(cartKey),
                Duration.ofSeconds(cartHubProperties.getRedis().getCartExpireSeconds()));
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
        stringRedisTemplate.opsForValue().set(metaKey, JsonUtil.toJson(meta),
                Duration.ofSeconds(cartHubProperties.getRedis().getCartExpireSeconds()));
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
            setCartExpire(cartKey);
            incrementVersion(cartKey);
        }
        return updated;
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
