package com.carhub.service;

import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.CreateSnapshotDTO;
import com.carhub.domain.dto.RestoreSnapshotDTO;
import com.carhub.domain.entity.CartSnapshotEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.CartSnapshotVO;
import com.carhub.domain.vo.CartVO;
import com.carhub.mapper.CartSnapshotMapper;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartSnapshotService {

    private final CartSnapshotMapper cartSnapshotMapper;
    private final CartRedisStorage cartRedisStorage;
    private final CartHubProperties cartHubProperties;

    private static final String SNAPSHOT_TYPE_AUTO = "auto";
    private static final String SNAPSHOT_TYPE_MANUAL = "manual";
    private static final String SNAPSHOT_TYPE_SHARE = "share";
    private static final String SNAPSHOT_TYPE_ORDER = "order";
    private static final int SNAPSHOT_ID_LENGTH = 24;
    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int DEFAULT_MANUAL_SNAPSHOT_LIMIT = 50;
    private static final int DEFAULT_AUTO_SNAPSHOT_EXPIRE_DAYS = 30;

    private static final Map<String, String> SNAPSHOT_TYPE_DESC_MAP = new HashMap<>();
    static {
        SNAPSHOT_TYPE_DESC_MAP.put(SNAPSHOT_TYPE_AUTO, "自动快照");
        SNAPSHOT_TYPE_DESC_MAP.put(SNAPSHOT_TYPE_MANUAL, "手动快照");
        SNAPSHOT_TYPE_DESC_MAP.put(SNAPSHOT_TYPE_SHARE, "分享快照");
        SNAPSHOT_TYPE_DESC_MAP.put(SNAPSHOT_TYPE_ORDER, "下单快照");
    }

    @Async("cartTaskExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void createDailySnapshotAsync(String tenantId, String bizType, String userId) {
        if (StringUtils.isBlank(userId)) {
            return;
        }
        try {
            List<CartItem> items = cartRedisStorage.getItems(tenantId, bizType, userId);
            if (items == null || items.isEmpty()) {
                return;
            }

            LocalDate today = LocalDate.now();
            CartSnapshotEntity existing = cartSnapshotMapper.findLatestAutoByDate(tenantId, bizType, userId, today);

            if (existing != null) {
                updateSnapshotItems(existing, items);
                log.debug("Daily auto snapshot updated: tenantId={}, bizType={}, userId={}, snapshotId={}",
                        tenantId, bizType, userId, existing.getSnapshotId());
            } else {
                String snapshotId = generateSnapshotId();
                String snapshotName = "自动快照 " + today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                saveSnapshot(tenantId, bizType, userId, snapshotId, snapshotName, SNAPSHOT_TYPE_AUTO, items);
                log.info("Daily auto snapshot created: tenantId={}, bizType={}, userId={}, snapshotId={}",
                        tenantId, bizType, userId, snapshotId);
            }
        } catch (Exception e) {
            log.error("Create daily snapshot failed: tenantId={}, bizType={}, userId={}", tenantId, bizType, userId, e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public CartSnapshotVO createManualSnapshot(CreateSnapshotDTO dto) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        validateUserId(userId);

        int manualCount = cartSnapshotMapper.countByUserAndType(tenantId, bizType, userId, SNAPSHOT_TYPE_MANUAL);
        if (manualCount >= DEFAULT_MANUAL_SNAPSHOT_LIMIT) {
            throw new BusinessException(ResultCode.SNAPSHOT_LIMIT_EXCEEDED.getCode(),
                    String.format("手动快照数量已达上限(%d)，请先删除部分历史快照", DEFAULT_MANUAL_SNAPSHOT_LIMIT));
        }

        List<CartItem> items = cartRedisStorage.getItems(tenantId, bizType, userId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY.getCode(), "购物车为空，无法创建快照");
        }

        String snapshotId = generateSnapshotId();
        String snapshotName = StringUtils.defaultIfBlank(
                dto.getSnapshotName(),
                "手动快照 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        );

        saveSnapshot(tenantId, bizType, userId, snapshotId, snapshotName, SNAPSHOT_TYPE_MANUAL, items);
        log.info("Manual snapshot created: tenantId={}, bizType={}, userId={}, snapshotId={}, name={}",
                tenantId, bizType, userId, snapshotId, snapshotName);

        return getSnapshotDetail(snapshotId);
    }

    public List<CartSnapshotVO> getSnapshotHistory(Integer limit) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        validateUserId(userId);

        int queryLimit = limit != null && limit > 0 ? limit : DEFAULT_HISTORY_LIMIT;
        List<CartSnapshotEntity> entities = cartSnapshotMapper.findByUser(tenantId, bizType, userId, queryLimit);

        return entities.stream()
                .map(this::toSimpleVO)
                .collect(Collectors.toList());
    }

    public CartSnapshotVO getSnapshotDetail(String snapshotId) {
        if (StringUtils.isBlank(snapshotId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "快照ID不能为空");
        }

        CartSnapshotEntity entity = cartSnapshotMapper.findBySnapshotId(snapshotId);
        if (entity == null) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }

        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        validateSnapshotOwner(entity, tenantId, bizType, userId);
        checkSnapshotExpired(entity);

        return toDetailVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public CartVO restoreSnapshot(RestoreSnapshotDTO dto) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        validateUserId(userId);

        CartSnapshotEntity entity = cartSnapshotMapper.findBySnapshotId(dto.getSnapshotId());
        if (entity == null) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }
        validateSnapshotOwner(entity, tenantId, bizType, userId);
        checkSnapshotExpired(entity);

        List<CartItem> snapshotItems = parseSnapshotItems(entity.getCartSnapshot());
        if (snapshotItems == null || snapshotItems.isEmpty()) {
            throw new BusinessException(ResultCode.SNAPSHOT_RESTORE_FAILED.getCode(), "快照数据为空");
        }

        if (!Boolean.TRUE.equals(dto.getForceOverwrite())) {
            Long currentVersion = cartRedisStorage.getVersion(tenantId, bizType, userId);
            if (dto.getClientVersion() != null && dto.getClientVersion() < currentVersion) {
                throw new BusinessException(ResultCode.CART_VERSION_CONFLICT);
            }
        }

        if (Boolean.TRUE.equals(dto.getMergeCurrent())) {
            restoreWithMerge(tenantId, bizType, userId, snapshotItems);
        } else {
            restoreWithOverwrite(tenantId, bizType, userId, snapshotItems);
        }

        log.info("Snapshot restored: tenantId={}, bizType={}, userId={}, snapshotId={}, merge={}",
                tenantId, bizType, userId, dto.getSnapshotId(), dto.getMergeCurrent());

        return buildCartVO(tenantId, bizType, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteSnapshot(String snapshotId) {
        if (StringUtils.isBlank(snapshotId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "快照ID不能为空");
        }

        CartSnapshotEntity entity = cartSnapshotMapper.findBySnapshotId(snapshotId);
        if (entity == null) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }

        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        validateSnapshotOwner(entity, tenantId, bizType, userId);

        entity.setDeleted(1);
        cartSnapshotMapper.updateById(entity);
        log.info("Snapshot deleted: snapshotId={}, userId={}", snapshotId, userId);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public int cleanupExpiredSnapshots(int batchSize) {
        List<String> expiredIds = cartSnapshotMapper.findExpiredSnapshotIds(LocalDateTime.now(), batchSize);
        if (expiredIds.isEmpty()) {
            return 0;
        }
        for (String snapshotId : expiredIds) {
            CartSnapshotEntity entity = cartSnapshotMapper.findBySnapshotId(snapshotId);
            if (entity != null) {
                entity.setDeleted(1);
                cartSnapshotMapper.updateById(entity);
            }
        }
        log.info("Cleaned up {} expired snapshots", expiredIds.size());
        return expiredIds.size();
    }

    private void restoreWithOverwrite(String tenantId, String bizType, String userId, List<CartItem> snapshotItems) {
        cartRedisStorage.clearCart(tenantId, bizType, userId);
        for (CartItem item : snapshotItems) {
            cartRedisStorage.addItem(tenantId, bizType, userId, item);
        }
    }

    private void restoreWithMerge(String tenantId, String bizType, String userId, List<CartItem> snapshotItems) {
        List<CartItem> currentItems = cartRedisStorage.getItems(tenantId, bizType, userId);
        Map<String, CartItem> mergedMap = new LinkedHashMap<>();

        if (currentItems != null) {
            for (CartItem item : currentItems) {
                if (item != null && StringUtils.isNotBlank(item.getSkuId())) {
                    mergedMap.put(item.getSkuId(), item);
                }
            }
        }

        for (CartItem snapshotItem : snapshotItems) {
            if (snapshotItem == null || StringUtils.isBlank(snapshotItem.getSkuId())) {
                continue;
            }
            String skuId = snapshotItem.getSkuId();
            if (mergedMap.containsKey(skuId)) {
                CartItem existing = mergedMap.get(skuId);
                int newQty = (existing.getQuantity() != null ? existing.getQuantity() : 0)
                        + (snapshotItem.getQuantity() != null ? snapshotItem.getQuantity() : 0);
                existing.setQuantity(newQty);
            } else {
                mergedMap.put(skuId, snapshotItem);
            }
        }

        cartRedisStorage.clearCart(tenantId, bizType, userId);
        for (CartItem item : mergedMap.values()) {
            cartRedisStorage.addItem(tenantId, bizType, userId, item);
        }
    }

    private void saveSnapshot(String tenantId, String bizType, String userId,
                              String snapshotId, String snapshotName, String snapshotType,
                              List<CartItem> items) {
        String itemsJson = JsonUtil.toJson(items);

        SnapshotStats stats = calculateStats(items);

        CartSnapshotEntity entity = new CartSnapshotEntity();
        entity.setSnapshotId(snapshotId);
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setUserId(userId);
        entity.setSnapshotName(snapshotName);
        entity.setSnapshotType(snapshotType);
        entity.setCartSnapshot(itemsJson);
        entity.setItemCount(stats.itemCount);
        entity.setTotalQuantity(stats.totalQuantity);
        entity.setTotalAmount(stats.totalAmount);
        entity.setStorageType(1);
        if (SNAPSHOT_TYPE_AUTO.equals(snapshotType)) {
            entity.setExpireTime(LocalDateTime.now().plusDays(DEFAULT_AUTO_SNAPSHOT_EXPIRE_DAYS));
        }
        cartSnapshotMapper.insert(entity);
    }

    private void updateSnapshotItems(CartSnapshotEntity entity, List<CartItem> items) {
        String itemsJson = JsonUtil.toJson(items);
        SnapshotStats stats = calculateStats(items);

        entity.setCartSnapshot(itemsJson);
        entity.setItemCount(stats.itemCount);
        entity.setTotalQuantity(stats.totalQuantity);
        entity.setTotalAmount(stats.totalAmount);
        entity.setUpdateTime(LocalDateTime.now());
        cartSnapshotMapper.updateById(entity);
    }

    private SnapshotStats calculateStats(List<CartItem> items) {
        int itemCount = items != null ? items.size() : 0;
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        if (items != null) {
            for (CartItem item : items) {
                if (item == null) continue;
                totalQuantity += item.getQuantity() != null ? item.getQuantity() : 0;
                if (item.getUnitPrice() != null && item.getQuantity() != null) {
                    totalAmount = totalAmount.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                }
            }
        }
        return new SnapshotStats(itemCount, totalQuantity, totalAmount);
    }

    private CartSnapshotVO toSimpleVO(CartSnapshotEntity entity) {
        return CartSnapshotVO.builder()
                .snapshotId(entity.getSnapshotId())
                .snapshotName(entity.getSnapshotName())
                .snapshotType(entity.getSnapshotType())
                .snapshotTypeDesc(SNAPSHOT_TYPE_DESC_MAP.getOrDefault(entity.getSnapshotType(), entity.getSnapshotType()))
                .itemCount(entity.getItemCount())
                .totalQuantity(entity.getTotalQuantity())
                .totalAmount(entity.getTotalAmount())
                .createTime(entity.getCreateTime())
                .expireTime(entity.getExpireTime())
                .orderNo(entity.getOrderNo())
                .build();
    }

    private CartSnapshotVO toDetailVO(CartSnapshotEntity entity) {
        CartSnapshotVO vo = toSimpleVO(entity);
        List<CartItem> items = parseSnapshotItems(entity.getCartSnapshot());
        vo.setItems(items);
        return vo;
    }

    private List<CartItem> parseSnapshotItems(String cartSnapshotJson) {
        if (StringUtils.isBlank(cartSnapshotJson)) {
            return Collections.emptyList();
        }
        try {
            return JsonUtil.fromJsonList(cartSnapshotJson, CartItem.class);
        } catch (Exception e) {
            log.debug("Parse snapshot as List<CartItem> failed, try Cart format", e);
            try {
                Cart cart = JsonUtil.fromJson(cartSnapshotJson, Cart.class);
                return cart.getItems() != null ? cart.getItems() : Collections.emptyList();
            } catch (Exception ex) {
                log.error("Parse snapshot items failed", ex);
                return Collections.emptyList();
            }
        }
    }

    private CartVO buildCartVO(String tenantId, String bizType, String userId) {
        List<CartItem> items = cartRedisStorage.getItems(tenantId, bizType, userId);
        SnapshotStats stats = calculateStats(items);
        Long version = cartRedisStorage.getVersion(tenantId, bizType, userId);
        return CartVO.builder()
                .tenantId(tenantId)
                .bizType(bizType)
                .userId(userId)
                .items(items)
                .itemCount(stats.itemCount)
                .totalQuantity(stats.totalQuantity)
                .totalAmount(stats.totalAmount)
                .version(version)
                .updateTime(System.currentTimeMillis())
                .build();
    }

    private void validateSnapshotOwner(CartSnapshotEntity entity, String tenantId, String bizType, String userId) {
        if (!Objects.equals(entity.getTenantId(), tenantId)
                || !Objects.equals(entity.getBizType(), bizType)
                || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }
    }

    private void checkSnapshotExpired(CartSnapshotEntity entity) {
        if (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.SNAPSHOT_EXPIRED);
        }
    }

    private String generateSnapshotId() {
        return "SS" + System.currentTimeMillis() + RandomStringUtils.randomNumeric(SNAPSHOT_ID_LENGTH - 12);
    }

    private void validateUserId(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }
    }

    private static class SnapshotStats {
        final int itemCount;
        final int totalQuantity;
        final BigDecimal totalAmount;

        SnapshotStats(int itemCount, int totalQuantity, BigDecimal totalAmount) {
            this.itemCount = itemCount;
            this.totalQuantity = totalQuantity;
            this.totalAmount = totalAmount;
        }
    }
}
