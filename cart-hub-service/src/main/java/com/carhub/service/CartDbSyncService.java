package com.carhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carhub.common.constant.CartConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.domain.entity.CartEntity;
import com.carhub.domain.entity.CartItemEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.mapper.CartItemMapper;
import com.carhub.mapper.CartMapper;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartDbSyncService {

    private static final String SYNC_QUEUE_KEY = "cart:sync:queue";
    private static final String SYNC_LOCK_KEY = "cart:sync:lock";

    private final CartRedisStorage cartRedisStorage;
    private final CartMapper cartMapper;
    private final CartItemMapper cartItemMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private org.redisson.api.RedissonClient redissonClient;

    public void markNeedSync(String tenantId, String bizType, String userId) {
        String key = buildSyncKey(tenantId, bizType, userId);
        try {
            stringRedisTemplate.opsForSet().add(SYNC_QUEUE_KEY, key);
        } catch (Exception e) {
            log.warn("markNeedSync error", e);
        }
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void syncSingle(String tenantId, String bizType, String userId) {
        if (StringUtils.isAnyBlank(tenantId, bizType, userId)) {
            return;
        }
        try {
            boolean locked = redissonClient.getLock(SYNC_LOCK_KEY + ":" + userId).tryLock(5, 60, TimeUnit.SECONDS);
            if (!locked) {
                return;
            }
            try {
                doSync(tenantId, bizType, userId);
            } finally {
                redissonClient.getLock(SYNC_LOCK_KEY + ":" + userId).unlock();
            }
        } catch (Exception e) {
            log.error("syncSingle error: tenantId={}, bizType={}, userId={}", tenantId, bizType, userId, e);
        }
    }

    @Scheduled(fixedDelayString = "${cart-hub.sync.sync-interval-seconds:300}000", initialDelay = 60000)
    public void batchSyncTask() {
        Set<String> members = Collections.emptySet();
        try {
            members = stringRedisTemplate.opsForSet().members(SYNC_QUEUE_KEY);
        } catch (Exception e) {
            log.warn("get sync queue error", e);
        }
        if (members == null || members.isEmpty()) {
            return;
        }
        log.info("batchSyncTask start, count={}", members.size());
        int count = 0;
        for (String key : members) {
            try {
                String[] parts = parseSyncKey(key);
                if (parts != null) {
                    syncSingle(parts[0], parts[1], parts[2]);
                    stringRedisTemplate.opsForSet().remove(SYNC_QUEUE_KEY, key);
                    count++;
                }
            } catch (Exception e) {
                log.error("batch sync item error, key={}", key, e);
            }
        }
        log.info("batchSyncTask done, synced={}", count);
    }

    private void doSync(String tenantId, String bizType, String userId) {
        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);

        CartEntity cartEntity = buildCartEntity(tenantId, bizType, userId, cart);
        cartMapper.upsertCart(cartEntity);

        Long cartId = cartEntity.getId();
        if (cartId == null) {
            LambdaQueryWrapper<CartEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(CartEntity::getTenantId, tenantId)
                    .eq(CartEntity::getBizType, bizType)
                    .eq(CartEntity::getUserId, userId)
                    .eq(CartEntity::getDeleted, 0);
            CartEntity exist = cartMapper.selectOne(wrapper);
            if (exist != null) {
                cartId = exist.getId();
            }
        }

        List<CartItemEntity> items = new ArrayList<>();
        if (cart.getItems() != null && cartId != null) {
            for (CartItem item : cart.getItems()) {
                items.add(buildCartItemEntity(tenantId, bizType, userId, cartId, item));
            }
        }

        LambdaUpdateWrapper<CartItemEntity> deleteWrapper = new LambdaUpdateWrapper<>();
        deleteWrapper.eq(CartItemEntity::getTenantId, tenantId)
                .eq(CartItemEntity::getBizType, bizType)
                .eq(CartItemEntity::getUserId, userId);
        Set<String> validSkus = items.stream()
                .map(CartItemEntity::getSkuId)
                .collect(Collectors.toSet());
        if (!validSkus.isEmpty()) {
            deleteWrapper.notIn(CartItemEntity::getSkuId, validSkus);
        }
        cartItemMapper.delete(deleteWrapper);

        if (!items.isEmpty()) {
            try {
                cartItemMapper.batchUpsertItems(items);
            } catch (Exception e) {
                log.warn("batchUpsertItems error, fallback to single insert", e);
                for (CartItemEntity item : items) {
                    try {
                        upsertSingleItem(item);
                    } catch (Exception ex) {
                        log.error("single upsert error, skuId={}", item.getSkuId(), ex);
                    }
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("doSync success: tenantId={}, bizType={}, userId={}, items={}",
                    tenantId, bizType, userId, items.size());
        }
    }

    private void upsertSingleItem(CartItemEntity item) {
        LambdaQueryWrapper<CartItemEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartItemEntity::getTenantId, item.getTenantId())
                .eq(CartItemEntity::getBizType, item.getBizType())
                .eq(CartItemEntity::getUserId, item.getUserId())
                .eq(CartItemEntity::getSkuId, item.getSkuId())
                .eq(CartItemEntity::getDeleted, 0);
        CartItemEntity exist = cartItemMapper.selectOne(wrapper);
        if (exist != null) {
            item.setId(exist.getId());
            cartItemMapper.updateById(item);
        } else {
            cartItemMapper.insert(item);
        }
    }

    private CartEntity buildCartEntity(String tenantId, String bizType, String userId, Cart cart) {
        CartEntity entity = new CartEntity();
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setUserId(userId);
        entity.setItemCount(cart.getItemCount() != null ? cart.getItemCount() : 0);
        entity.setTotalQuantity(cart.getTotalQuantity() != null ? cart.getTotalQuantity() : 0);
        entity.setTotalAmount(cart.getTotalAmount() != null ? cart.getTotalAmount() : BigDecimal.ZERO);
        entity.setDiscountAmount(cart.getDiscountAmount() != null ? cart.getDiscountAmount() : BigDecimal.ZERO);
        entity.setPayAmount(cart.getPayAmount() != null ? cart.getPayAmount() : BigDecimal.ZERO);
        entity.setLastSyncTime(LocalDateTime.now());
        entity.setVersion(cart.getVersion() != null ? cart.getVersion() : 0L);
        return entity;
    }

    private CartItemEntity buildCartItemEntity(String tenantId, String bizType, String userId,
                                                Long cartId, CartItem item) {
        CartItemEntity entity = new CartItemEntity();
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setUserId(userId);
        entity.setCartId(cartId);
        entity.setSkuId(item.getSkuId());
        entity.setSpuId(item.getSpuId());
        entity.setCategoryId(item.getCategoryId());
        entity.setShopId(item.getShopId());
        entity.setItemName(item.getItemName());
        entity.setItemImage(item.getItemImage());
        entity.setItemSpec(item.getItemSpec() != null ? JsonUtil.toJson(item.getItemSpec()) : null);
        entity.setUnitPrice(item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
        entity.setOriginalPrice(item.getOriginalPrice());
        entity.setQuantity(item.getQuantity() != null ? item.getQuantity() : 1);
        entity.setSubtotal(item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO);
        entity.setDiscountAmount(item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO);
        entity.setPayAmount(item.getPayAmount() != null ? item.getPayAmount() : BigDecimal.ZERO);
        entity.setStock(item.getStock());
        entity.setOnShelf(Boolean.TRUE.equals(item.getOnShelf()) ? 1 : 0);
        entity.setSelected(Boolean.TRUE.equals(item.getSelected()) ? 1 : 0);
        entity.setPriceChanged(Boolean.TRUE.equals(item.getPriceChanged()) ? 1 : 0);
        entity.setOldPrice(item.getOldPrice());
        if (item.getAddTime() != null) {
            entity.setAddTime(new java.sql.Date(item.getAddTime()).toLocalDate().atStartOfDay());
        }
        entity.setAddSource(item.getAddSource());
        entity.setExtInfo(item.getExtInfo() != null ? JsonUtil.toJson(item.getExtInfo()) : null);
        return entity;
    }

    private String buildSyncKey(String tenantId, String bizType, String userId) {
        return tenantId + ":" + bizType + ":" + userId;
    }

    private String[] parseSyncKey(String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }
        String[] parts = key.split(":");
        if (parts.length >= 3) {
            return new String[]{parts[0], parts[1], parts[2]};
        }
        return null;
    }

}
