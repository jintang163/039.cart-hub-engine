package com.carhub.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carhub.common.constant.CartConstant;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.entity.CartArchiveEntity;
import com.carhub.domain.entity.CartEntity;
import com.carhub.domain.entity.CartItemArchiveEntity;
import com.carhub.domain.entity.CartItemEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.mapper.CartArchiveMapper;
import com.carhub.mapper.CartItemArchiveMapper;
import com.carhub.mapper.CartItemMapper;
import com.carhub.mapper.CartMapper;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartExpireCleanupService {

    private final CartRedisStorage cartRedisStorage;
    private final CartNotificationService cartNotificationService;
    private final CartArchiveMapper cartArchiveMapper;
    private final CartItemArchiveMapper cartItemArchiveMapper;
    private final CartMapper cartMapper;
    private final CartItemMapper cartItemMapper;
    private final CartHubProperties cartHubProperties;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Scheduled(cron = "${cart-hub.cleanup.cron:0 0 2 * * ?}")
    public void scheduledCleanupTask() {
        if (!Boolean.TRUE.equals(cartHubProperties.getCleanup().getEnable())) {
            return;
        }
        boolean locked = false;
        try {
            locked = redissonClient.getLock(RedisKeyConstant.CART_CLEANUP_LOCK_KEY)
                    .tryLock(0, 3600, TimeUnit.SECONDS);
            if (!locked) {
                log.info("Cleanup task already running, skip");
                return;
            }
            log.info("Cart expire cleanup task started");
            doCleanup();
            log.info("Cart expire cleanup task completed");
        } catch (Exception e) {
            log.error("Cart expire cleanup task failed", e);
        } finally {
            if (locked) {
                try {
                    redissonClient.getLock(RedisKeyConstant.CART_CLEANUP_LOCK_KEY).unlock();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void doCleanup() {
        String tenantId = CartConstant.DEFAULT_TENANT_ID;
        String bizType = CartConstant.BIZ_TYPE_ECOMMERCE;

        int retentionDays = cartRedisStorage.resolveItemRetentionDays(tenantId, bizType);
        int remindDays = cartRedisStorage.resolveRemindBeforeDays(tenantId, bizType);
        int batchSize = cartHubProperties.getCleanup().getBatchSize();
        long now = System.currentTimeMillis();
        long expireBeforeTs = now - retentionDays * 86400_000L;
        long remindBeforeTs = now - (retentionDays - remindDays) * 86400_000L;

        int totalExpired = 0;
        int totalReminded = 0;
        int totalArchived = 0;

        while (true) {
            List<String> expiringUsers = cartRedisStorage.scanExpiringCarts(
                    tenantId, bizType, remindBeforeTs, expireBeforeTs, batchSize);
            for (String userId : expiringUsers) {
                try {
                    if (!cartRedisStorage.hasExpireReminded(tenantId, bizType, userId)) {
                        Cart cart = cartRedisStorage.getCartReadOnly(tenantId, bizType, userId);
                        if (cart != null && cart.getItems() != null && !cart.getItems().isEmpty()) {
                            long daysLeft = (cart.getExpireTime() != null ? cart.getExpireTime() - now : 0) / 86400_000L;
                            cartNotificationService.sendExpireReminder(tenantId, bizType, userId, cart, (int) daysLeft);
                            cartRedisStorage.setExpireReminded(tenantId, bizType, userId, remindDays);
                            totalReminded++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Process expiring cart error, userId={}", userId, e);
                }
            }
            if (expiringUsers.size() < batchSize) {
                break;
            }
        }

        while (true) {
            List<String> expiredUsers = cartRedisStorage.scanExpiredCarts(
                    tenantId, bizType, expireBeforeTs, batchSize);
            if (expiredUsers.isEmpty()) {
                break;
            }
            for (String userId : expiredUsers) {
                try {
                    int archived = archiveAndCleanup(tenantId, bizType, userId);
                    totalExpired++;
                    totalArchived += archived;
                } catch (Exception e) {
                    log.error("Archive and cleanup error, userId={}", userId, e);
                }
            }
        }

        if (Boolean.TRUE.equals(cartHubProperties.getCleanup().getArchiveToDb())) {
            cleanOldArchives();
        }

        recordCleanupStats(totalExpired, totalReminded, totalArchived);
        log.info("Cleanup summary: expired={}, reminded={}, archived={}", totalExpired, totalReminded, totalArchived);
    }

    @Transactional(rollbackFor = Exception.class)
    public int archiveAndCleanup(String tenantId, String bizType, String userId) {
        Cart cart = cartRedisStorage.getCartReadOnly(tenantId, bizType, userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            cartRedisStorage.clearCart(tenantId, bizType, userId);
            return 0;
        }

        int archivedCount = 0;
        if (Boolean.TRUE.equals(cartHubProperties.getCleanup().getArchiveToDb())) {
            LocalDateTime archiveTime = LocalDateTime.now();
            LocalDateTime lastAccessTime = cart.getLastAccessTime() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochMilli(cart.getLastAccessTime()), ZoneId.systemDefault())
                    : archiveTime;

            CartArchiveEntity archiveEntity = new CartArchiveEntity();
            archiveEntity.setTenantId(tenantId);
            archiveEntity.setBizType(bizType);
            archiveEntity.setUserId(userId);
            archiveEntity.setItemCount(cart.getItemCount() != null ? cart.getItemCount() : 0);
            archiveEntity.setTotalQuantity(cart.getTotalQuantity() != null ? cart.getTotalQuantity() : 0);
            archiveEntity.setTotalAmount(cart.getTotalAmount() != null ? cart.getTotalAmount() : BigDecimal.ZERO);
            archiveEntity.setDiscountAmount(cart.getDiscountAmount() != null ? cart.getDiscountAmount() : BigDecimal.ZERO);
            archiveEntity.setPayAmount(cart.getPayAmount() != null ? cart.getPayAmount() : BigDecimal.ZERO);
            archiveEntity.setLastAccessTime(lastAccessTime);
            archiveEntity.setArchiveTime(archiveTime);
            archiveEntity.setArchiveReason("expired_auto_cleanup");
            archiveEntity.setVersion(cart.getVersion());
            cartArchiveMapper.insert(archiveEntity);

            List<CartItemArchiveEntity> itemArchives = new ArrayList<>();
            for (CartItem item : cart.getItems()) {
                CartItemArchiveEntity itemArchive = new CartItemArchiveEntity();
                itemArchive.setTenantId(tenantId);
                itemArchive.setBizType(bizType);
                itemArchive.setUserId(userId);
                itemArchive.setCartArchiveId(archiveEntity.getId());
                itemArchive.setSkuId(item.getSkuId());
                itemArchive.setSpuId(item.getSpuId());
                itemArchive.setCategoryId(item.getCategoryId());
                itemArchive.setShopId(item.getShopId());
                itemArchive.setItemName(item.getItemName());
                itemArchive.setItemImage(item.getItemImage());
                itemArchive.setItemSpec(item.getItemSpec() != null ? JsonUtil.toJson(item.getItemSpec()) : null);
                itemArchive.setUnitPrice(item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
                itemArchive.setOriginalPrice(item.getOriginalPrice());
                itemArchive.setQuantity(item.getQuantity() != null ? item.getQuantity() : 1);
                itemArchive.setSubtotal(item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO);
                itemArchive.setDiscountAmount(item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO);
                itemArchive.setPayAmount(item.getPayAmount() != null ? item.getPayAmount() : BigDecimal.ZERO);
                itemArchive.setStock(item.getStock());
                itemArchive.setOnShelf(Boolean.TRUE.equals(item.getOnShelf()) ? 1 : 0);
                itemArchive.setSelected(Boolean.TRUE.equals(item.getSelected()) ? 1 : 0);
                itemArchive.setPriceChanged(Boolean.TRUE.equals(item.getPriceChanged()) ? 1 : 0);
                itemArchive.setOldPrice(item.getOldPrice());
                if (item.getAddTime() != null) {
                    itemArchive.setAddTime(LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(item.getAddTime()), ZoneId.systemDefault()));
                }
                itemArchive.setAddSource(item.getAddSource());
                itemArchive.setLastAccessTime(lastAccessTime);
                itemArchive.setArchiveTime(archiveTime);
                itemArchive.setExtInfo(item.getExtInfo() != null ? JsonUtil.toJson(item.getExtInfo()) : null);
                itemArchives.add(itemArchive);
            }
            if (!itemArchives.isEmpty()) {
                cartItemArchiveMapper.batchInsert(itemArchives);
                archivedCount = itemArchives.size();
            }

            LambdaUpdateWrapper<CartEntity> cartDeleteWrapper = new LambdaUpdateWrapper<>();
            cartDeleteWrapper.eq(CartEntity::getTenantId, tenantId)
                    .eq(CartEntity::getBizType, bizType)
                    .eq(CartEntity::getUserId, userId);
            cartMapper.delete(cartDeleteWrapper);

            LambdaUpdateWrapper<CartItemEntity> itemDeleteWrapper = new LambdaUpdateWrapper<>();
            itemDeleteWrapper.eq(CartItemEntity::getTenantId, tenantId)
                    .eq(CartItemEntity::getBizType, bizType)
                    .eq(CartItemEntity::getUserId, userId);
            cartItemMapper.delete(itemDeleteWrapper);
        }

        cartNotificationService.sendCleanupNotification(tenantId, bizType, userId, cart, "expired_auto_cleanup");

        cartRedisStorage.clearCart(tenantId, bizType, userId);

        return archivedCount;
    }

    private void cleanOldArchives() {
        try {
            Integer archiveDays = cartHubProperties.getCleanup().getArchiveRetentionDays();
            if (archiveDays == null || archiveDays <= 0) {
                return;
            }
            LocalDateTime beforeTime = LocalDateTime.now().minusDays(archiveDays);
            int limit = 500;
            int total = 0;
            while (true) {
                int deleted = cartArchiveMapper.cleanExpiredArchives(beforeTime, limit);
                cartItemArchiveMapper.cleanExpiredArchives(beforeTime, limit);
                total += deleted;
                if (deleted < limit) {
                    break;
                }
            }
            if (total > 0) {
                log.info("Cleaned {} old archive records", total);
            }
        } catch (Exception e) {
            log.error("Clean old archives failed", e);
        }
    }

    private void recordCleanupStats(int expiredCount, int remindedCount, int archivedCount) {
        try {
            String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String key = RedisKeyConstant.buildCleanupStatKey(date);
            Map<String, Object> stats = new HashMap<>();
            stats.put("date", date);
            stats.put("expiredCount", expiredCount);
            stats.put("remindedCount", remindedCount);
            stats.put("archivedCount", archivedCount);
            stats.put("timestamp", System.currentTimeMillis());
            stringRedisTemplate.opsForValue().set(key, JsonUtil.toJson(stats),
                    java.time.Duration.ofDays(30));
        } catch (Exception e) {
            log.warn("Record cleanup stats failed", e);
        }
    }

    public Map<String, Object> getExpireInfo(String tenantId, String bizType, String userId) {
        Map<String, Object> result = new HashMap<>();
        Long lastAccessTime = cartRedisStorage.getLastAccessTime(tenantId, bizType, userId);
        result.put("lastAccessTime", lastAccessTime);

        int retentionDays = cartRedisStorage.resolveItemRetentionDays(tenantId, bizType);
        int remindBeforeDays = cartRedisStorage.resolveRemindBeforeDays(tenantId, bizType);
        result.put("retentionDays", retentionDays);
        result.put("remindBeforeDays", remindBeforeDays);

        if (lastAccessTime != null) {
            long expireTime = lastAccessTime + retentionDays * 86400_000L;
            long now = System.currentTimeMillis();
            long msLeft = expireTime - now;
            long daysLeft = msLeft / 86400_000L;
            long hoursLeft = (msLeft % 86400_000L) / 3600_000L;

            result.put("expireTime", expireTime);
            result.put("daysLeft", daysLeft);
            result.put("hoursLeft", hoursLeft);
            result.put("isExpiring", daysLeft <= remindBeforeDays);
            result.put("isExpired", msLeft <= 0);
            result.put("hasReminded", cartRedisStorage.hasExpireReminded(tenantId, bizType, userId));
        } else {
            result.put("daysLeft", retentionDays);
            result.put("isExpiring", false);
            result.put("isExpired", false);
            result.put("hasReminded", false);
        }

        Cart cart = cartRedisStorage.getCartReadOnly(tenantId, bizType, userId);
        if (cart != null) {
            result.put("itemCount", cart.getItemCount());
        }

        return result;
    }

    public void triggerManualCleanup() {
        scheduledCleanupTask();
    }

}
