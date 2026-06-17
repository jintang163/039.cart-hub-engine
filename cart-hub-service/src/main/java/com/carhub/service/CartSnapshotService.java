package com.carhub.service;

import com.carhub.common.context.CartContextHolder;
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
    private final CartService cartService;
    private final CartHubProperties cartHubProperties;

    private static final String SNAPSHOT_TYPE_AUTO = "auto";
    private static final String SNAPSHOT_TYPE_MANUAL = "manual";
    private static final int SNAPSHOT_ID_LENGTH = 24;
    private static final int DEFAULT_HISTORY_LIMIT = 100;
    private static final int DEFAULT_MANUAL_SNAPSHOT_LIMIT = 50;
    private static final int DEFAULT_AUTO_SNAPSHOT_EXPIRE_DAYS = 30;

    private static final Map<String, String> SNAPSHOT_TYPE_DESC_MAP = new HashMap<>();
    static {
        SNAPSHOT_TYPE_DESC_MAP.put(SNAPSHOT_TYPE_AUTO, "自动快照");
        SNAPSHOT_TYPE_DESC_MAP.put(SNAPSHOT_TYPE_MANUAL, "手动快照");
        SNAPSHOT_TYPE_DESC_MAP.put("share", "分享快照");
        SNAPSHOT_TYPE_DESC_MAP.put("order", "下单快照");
    }

    @Async("cartTaskExecutor")
    @Transactional(rollbackFor = Exception.class)
    public void createDailySnapshotIfAbsent() {
        try {
            String tenantId = CartContextHolder.getTenantId();
            String bizType = CartContextHolder.getBizType();
            String userId = CartContextHolder.getUserId();
            if (StringUtils.isBlank(userId)) {
                return;
            }

            Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
            if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
                return;
            }

            LocalDate today = LocalDate.now();
            CartSnapshotEntity existing = cartSnapshotMapper.findLatestAutoByDate(tenantId, bizType, userId, today);
            if (existing != null) {
                return;
            }

            String snapshotId = generateSnapshotId();
            String snapshotName = "自动快照 " + today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            saveSnapshot(tenantId, bizType, userId, snapshotId, snapshotName, SNAPSHOT_TYPE_AUTO, cart);
            log.info("Daily auto snapshot created: tenantId={}, bizType={}, userId={}, snapshotId={}",
                    tenantId, bizType, userId, snapshotId);
        } catch (Exception e) {
            log.error("Create daily snapshot failed", e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public CartSnapshotVO createManualSnapshot(CreateSnapshotDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        int manualCount = cartSnapshotMapper.countByUserAndType(tenantId, bizType, userId, SNAPSHOT_TYPE_MANUAL);
        if (manualCount >= DEFAULT_MANUAL_SNAPSHOT_LIMIT) {
            throw new BusinessException(ResultCode.SNAPSHOT_LIMIT_EXCEEDED.getCode(),
                    String.format("手动快照数量已达上限(%d)，请先删除部分历史快照", DEFAULT_MANUAL_SNAPSHOT_LIMIT));
        }

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY.getCode(), "购物车为空，无法创建快照");
        }

        String snapshotId = generateSnapshotId();
        String snapshotName = StringUtils.defaultIfBlank(
                dto.getSnapshotName(),
                "手动快照 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        );

        saveSnapshot(tenantId, bizType, userId, snapshotId, snapshotName, SNAPSHOT_TYPE_MANUAL, cart);
        log.info("Manual snapshot created: tenantId={}, bizType={}, userId={}, snapshotId={}, name={}",
                tenantId, bizType, userId, snapshotId, snapshotName);

        return getSnapshotDetail(snapshotId);
    }

    public List<CartSnapshotVO> getSnapshotHistory(Integer limit) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
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

        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        if (!Objects.equals(entity.getTenantId(), tenantId)
                || !Objects.equals(entity.getBizType(), bizType)
                || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }

        if (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.SNAPSHOT_EXPIRED);
        }

        return toDetailVO(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public CartVO restoreSnapshot(RestoreSnapshotDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        CartSnapshotEntity entity = cartSnapshotMapper.findBySnapshotId(dto.getSnapshotId());
        if (entity == null) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }
        if (!Objects.equals(entity.getTenantId(), tenantId)
                || !Objects.equals(entity.getBizType(), bizType)
                || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }
        if (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.SNAPSHOT_EXPIRED);
        }

        List<CartItem> snapshotItems = parseSnapshotItems(entity.getCartSnapshot());
        if (snapshotItems == null || snapshotItems.isEmpty()) {
            throw new BusinessException(ResultCode.SNAPSHOT_RESTORE_FAILED.getCode(), "快照数据为空");
        }

        if (Boolean.TRUE.equals(dto.getMergeCurrent())) {
            return restoreWithMerge(tenantId, bizType, userId, snapshotItems, dto);
        } else {
            return restoreWithOverwrite(tenantId, bizType, userId, snapshotItems, dto);
        }
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

        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        if (!Objects.equals(entity.getTenantId(), tenantId)
                || !Objects.equals(entity.getBizType(), bizType)
                || !Objects.equals(entity.getUserId(), userId)) {
            throw new BusinessException(ResultCode.SNAPSHOT_NOT_FOUND);
        }

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

    private CartVO restoreWithOverwrite(String tenantId, String bizType, String userId,
                                        List<CartItem> snapshotItems, RestoreSnapshotDTO dto) {
        if (!Boolean.TRUE.equals(dto.getForceOverwrite())) {
            Long currentVersion = cartRedisStorage.getVersion(tenantId, bizType, userId);
            if (dto.getClientVersion() != null && dto.getClientVersion() < currentVersion) {
                throw new BusinessException(ResultCode.CART_VERSION_CONFLICT);
            }
        }

        cartRedisStorage.clearCart(tenantId, bizType, userId);
        for (CartItem item : snapshotItems) {
            cartRedisStorage.addItem(tenantId, bizType, userId, item);
        }

        log.info("Snapshot restored (overwrite): tenantId={}, bizType={}, userId={}, snapshotId={}, itemCount={}",
                tenantId, bizType, userId, dto.getSnapshotId(), snapshotItems.size());
        return cartService.getCartSimple();
    }

    private CartVO restoreWithMerge(String tenantId, String bizType, String userId,
                                    List<CartItem> snapshotItems, RestoreSnapshotDTO dto) {
        if (!Boolean.TRUE.equals(dto.getForceOverwrite())) {
            Long currentVersion = cartRedisStorage.getVersion(tenantId, bizType, userId);
            if (dto.getClientVersion() != null && dto.getClientVersion() < currentVersion) {
                throw new BusinessException(ResultCode.CART_VERSION_CONFLICT);
            }
        }

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

        log.info("Snapshot restored (merge): tenantId={}, bizType={}, userId={}, snapshotId={}, mergedCount={}",
                tenantId, bizType, userId, dto.getSnapshotId(), mergedMap.size());
        return cartService.getCartSimple();
    }

    private void saveSnapshot(String tenantId, String bizType, String userId,
                              String snapshotId, String snapshotName, String snapshotType, Cart cart) {
        String cartSnapshotJson = JsonUtil.toJson(cart.getItems());

        int itemCount = cart.getItems() != null ? cart.getItems().size() : 0;
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        if (cart.getItems() != null) {
            for (CartItem item : cart.getItems()) {
                if (item == null) continue;
                totalQuantity += item.getQuantity() != null ? item.getQuantity() : 0;
                if (item.getUnitPrice() != null && item.getQuantity() != null) {
                    totalAmount = totalAmount.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                }
            }
        }

        CartSnapshotEntity entity = new CartSnapshotEntity();
        entity.setSnapshotId(snapshotId);
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setUserId(userId);
        entity.setSnapshotName(snapshotName);
        entity.setSnapshotType(snapshotType);
        entity.setCartSnapshot(cartSnapshotJson);
        entity.setItemCount(itemCount);
        entity.setTotalQuantity(totalQuantity);
        entity.setTotalAmount(totalAmount);
        entity.setStorageType(1);
        if (SNAPSHOT_TYPE_AUTO.equals(snapshotType)) {
            entity.setExpireTime(LocalDateTime.now().plusDays(DEFAULT_AUTO_SNAPSHOT_EXPIRE_DAYS));
        }
        cartSnapshotMapper.insert(entity);
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
            log.error("Parse snapshot items failed", e);
            return Collections.emptyList();
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
}
