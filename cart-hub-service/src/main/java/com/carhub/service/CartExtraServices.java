package com.carhub.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.entity.*;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartDiscount;
import com.carhub.domain.model.CartItem;
import com.carhub.mapper.*;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
public class CartHistoryService {

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @org.springframework.scheduling.annotation.Async
    public void recordHistory(String tenantId, String bizType, String userId, String action,
                              String skuId, Integer oldQty, Integer newQty,
                              BigDecimal oldPrice, BigDecimal newPrice, String remark) {
        try {
            String traceId = com.carhub.common.context.CartContextHolder.getContext().getTraceId();
            String clientIp = com.carhub.common.context.CartContextHolder.getContext().getClientIp();
            String source = com.carhub.common.context.CartContextHolder.getContext().getSource();

            Map<String, Object> detail = new HashMap<>();
            if (skuId != null) detail.put("skuId", skuId);
            if (oldQty != null) detail.put("oldQuantity", oldQty);
            if (newQty != null) detail.put("newQuantity", newQty);
            if (oldPrice != null) detail.put("oldPrice", oldPrice);
            if (newPrice != null) detail.put("newPrice", newPrice);

            String key = "cart:history:queue";
            Map<String, Object> record = new HashMap<>();
            record.put("tenantId", tenantId);
            record.put("bizType", bizType);
            record.put("userId", userId);
            record.put("action", action);
            record.put("skuId", skuId);
            record.put("oldQuantity", oldQty);
            record.put("newQuantity", newQty);
            record.put("oldPrice", oldPrice != null ? oldPrice.toString() : null);
            record.put("newPrice", newPrice != null ? newPrice.toString() : null);
            record.put("operator", userId);
            record.put("operatorType", "user");
            record.put("source", source);
            record.put("clientIp", clientIp);
            record.put("remark", remark);
            record.put("detail", JsonUtil.toJson(detail));
            record.put("traceId", traceId);
            record.put("createTime", System.currentTimeMillis());

            stringRedisTemplate.opsForList().leftPush(key, JsonUtil.toJson(record));
            stringRedisTemplate.expire(key, 1, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("record history error", e);
        }
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class CartShareService {

    private final CartRedisStorage cartRedisStorage;
    private final CartShareMapper cartShareMapper;
    private final CartHubProperties cartHubProperties;
    private final CartService cartService;
    private final CartStatisticsService cartStatisticsService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createShare(String title, Integer expireHours, String password,
                                           Integer shareType) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY);
        }

        String shareId = IdUtil.fastSimpleUUID();
        int expire = expireHours != null ? expireHours : cartHubProperties.getRedis().getShareExpireSeconds() / 3600;

        CartShareEntity entity = new CartShareEntity();
        entity.setShareId(shareId);
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setOwnerId(userId);
        entity.setTitle(title);
        entity.setCartSnapshot(JsonUtil.toJson(cart));
        entity.setItemCount(cart.getItemCount() != null ? cart.getItemCount() : 0);
        entity.setTotalQuantity(cart.getTotalQuantity() != null ? cart.getTotalQuantity() : 0);
        entity.setTotalAmount(cart.getTotalAmount() != null ? cart.getTotalAmount() : BigDecimal.ZERO);
        entity.setShareType(shareType != null ? shareType : 1);
        entity.setExpireTime(LocalDateTime.now().plusHours(expire));
        entity.setPassword(password);
        cartShareMapper.insert(entity);

        cartService.saveShareCache(shareId, cart);

        cartStatisticsService.recordShare(tenantId, bizType, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("shareId", shareId);
        result.put("expireTime", entity.getExpireTime());
        result.put("needPassword", StringUtils.isNotBlank(password));
        return result;
    }

    public Cart viewShare(String shareId, String password) {
        if (StringUtils.isBlank(shareId)) {
            throw new BusinessException(ResultCode.CART_SHARE_NOT_FOUND);
        }
        CartShareEntity entity = getShareEntity(shareId);
        if (entity.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.CART_SHARE_EXPIRED);
        }
        if (StringUtils.isNotBlank(entity.getPassword())
                && !entity.getPassword().equals(password)) {
            throw new BusinessException("访问密码错误");
        }

        LambdaUpdateWrapper<CartShareEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(CartShareEntity::getId, entity.getId())
                .setSql("view_count = view_count + 1");
        cartShareMapper.update(null, wrapper);

        Cart cart = cartService.getShareCache(shareId);
        if (cart == null) {
            cart = JsonUtil.fromJson(entity.getCartSnapshot(), Cart.class);
            cartService.saveShareCache(shareId, cart);
        }
        return cart;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean acceptShare(String shareId, String password) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart sharedCart = viewShare(shareId, password);
        if (!tenantId.equals(sharedCart.getTenantId())
                || !bizType.equals(sharedCart.getBizType())) {
            throw new BusinessException("分享的购物车不属于当前业务");
        }

        List<CartItem> items = sharedCart.getItems();
        if (items == null || items.isEmpty()) {
            return true;
        }

        for (CartItem item : items) {
            cartRedisStorage.mergeUpdateItem(tenantId, bizType, userId, item, false);
        }

        LambdaUpdateWrapper<CartShareEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(CartShareEntity::getShareId, shareId)
                .setSql("accept_count = accept_count + 1");
        cartShareMapper.update(null, wrapper);

        return true;
    }

    private CartShareEntity getShareEntity(String shareId) {
        LambdaQueryWrapper<CartShareEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartShareEntity::getShareId, shareId)
                .eq(CartShareEntity::getDeleted, 0);
        CartShareEntity entity = cartShareMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException(ResultCode.CART_SHARE_NOT_FOUND);
        }
        return entity;
    }

    public List<CartShareEntity> listMyShares() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        LambdaQueryWrapper<CartShareEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartShareEntity::getTenantId, tenantId)
                .eq(CartShareEntity::getBizType, bizType)
                .eq(CartShareEntity::getOwnerId, userId)
                .eq(CartShareEntity::getDeleted, 0)
                .orderByDesc(CartShareEntity::getCreateTime);
        return cartShareMapper.selectList(wrapper);
    }

    public boolean cancelShare(String shareId) {
        String userId = CartContextHolder.getUserId();
        CartShareEntity entity = getShareEntity(shareId);
        if (!userId.equals(entity.getOwnerId())) {
            throw new BusinessException("无权限取消");
        }
        return cartShareMapper.deleteById(entity.getId()) > 0;
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class CartSnapshotService {

    private final CartRedisStorage cartRedisStorage;
    private final CartSnapshotMapper cartSnapshotMapper;
    private final CartHubProperties cartHubProperties;
    private final CartStatisticsService cartStatisticsService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createSnapshot(String snapshotName, String snapshotType, String orderNo) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY);
        }

        String snapshotId = IdUtil.fastSimpleUUID();
        int expireDays = cartHubProperties.getRedis().getSnapshotExpireSeconds() / 86400;

        CartSnapshotEntity entity = new CartSnapshotEntity();
        entity.setSnapshotId(snapshotId);
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setUserId(userId);
        entity.setSnapshotName(snapshotName);
        entity.setSnapshotType(StringUtils.defaultIfBlank(snapshotType, "manual"));
        entity.setCartSnapshot(JsonUtil.toJson(cart));
        entity.setItemCount(cart.getItemCount() != null ? cart.getItemCount() : 0);
        entity.setTotalQuantity(cart.getTotalQuantity() != null ? cart.getTotalQuantity() : 0);
        entity.setTotalAmount(cart.getTotalAmount() != null ? cart.getTotalAmount() : BigDecimal.ZERO);
        entity.setOrderNo(orderNo);
        entity.setStorageType(1);
        entity.setExpireTime(LocalDateTime.now().plusDays(expireDays));
        cartSnapshotMapper.insert(entity);

        cartStatisticsService.recordSnapshot(tenantId, bizType, userId);

        Map<String, Object> result = new HashMap<>();
        result.put("snapshotId", snapshotId);
        result.put("expireTime", entity.getExpireTime());
        return result;
    }

    public Cart getSnapshot(String snapshotId) {
        if (StringUtils.isBlank(snapshotId)) {
            throw new BusinessException("快照不存在");
        }
        String tenantId = CartContextHolder.getTenantId();
        LambdaQueryWrapper<CartSnapshotEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartSnapshotEntity::getSnapshotId, snapshotId)
                .eq(CartSnapshotEntity::getTenantId, tenantId)
                .eq(CartSnapshotEntity::getDeleted, 0);
        CartSnapshotEntity entity = cartSnapshotMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException("快照不存在");
        }
        return JsonUtil.fromJson(entity.getCartSnapshot(), Cart.class);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean restoreSnapshot(String snapshotId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart snapshot = getSnapshot(snapshotId);
        cartRedisStorage.clearCart(tenantId, bizType, userId);
        if (snapshot.getItems() != null) {
            for (CartItem item : snapshot.getItems()) {
                cartRedisStorage.addItem(tenantId, bizType, userId, item);
            }
        }
        return true;
    }

    public List<CartSnapshotEntity> listMySnapshots() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        LambdaQueryWrapper<CartSnapshotEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartSnapshotEntity::getTenantId, tenantId)
                .eq(CartSnapshotEntity::getBizType, bizType)
                .eq(CartSnapshotEntity::getUserId, userId)
                .eq(CartSnapshotEntity::getDeleted, 0)
                .orderByDesc(CartSnapshotEntity::getCreateTime)
                .last("LIMIT 100");
        return cartSnapshotMapper.selectList(wrapper);
    }

    public boolean deleteSnapshot(String snapshotId) {
        String userId = CartContextHolder.getUserId();
        CartSnapshotEntity entity = getSnapshotEntity(snapshotId);
        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException("无权限删除");
        }
        return cartSnapshotMapper.deleteById(entity.getId()) > 0;
    }

    private CartSnapshotEntity getSnapshotEntity(String snapshotId) {
        String tenantId = CartContextHolder.getTenantId();
        LambdaQueryWrapper<CartSnapshotEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartSnapshotEntity::getSnapshotId, snapshotId)
                .eq(CartSnapshotEntity::getTenantId, tenantId)
                .eq(CartSnapshotEntity::getDeleted, 0);
        CartSnapshotEntity entity = cartSnapshotMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException("快照不存在");
        }
        return entity;
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class CartDiscountService {

    private final CartRedisStorage cartRedisStorage;
    private final CartDiscountMapper cartDiscountMapper;

    public Cart applyDiscount(String discountId, String discountType, String discountName,
                              String discountCode, BigDecimal discountAmount,
                              List<String> applySkus, String scope) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart.getDiscounts() == null) {
            cart.setDiscounts(new ArrayList<>());
        }

        CartDiscount discount = CartDiscount.builder()
                .discountId(discountId)
                .discountType(discountType)
                .discountName(discountName)
                .discountCode(discountCode)
                .discountAmount(discountAmount)
                .scope(StringUtils.defaultIfBlank(scope, "all"))
                .applySkus(applySkus)
                .enable(true)
                .build();

        cart.getDiscounts().removeIf(d -> discountId.equals(d.getDiscountId()));
        cart.getDiscounts().add(discount);
        cart.recalculate();
        return cart;
    }

    public Cart removeDiscount(String discountId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart.getDiscounts() != null) {
            cart.getDiscounts().removeIf(d -> discountId.equals(d.getDiscountId()));
            cart.recalculate();
        }
        return cart;
    }

    public List<CartDiscount> listDiscounts() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        return cart.getDiscounts() != null ? cart.getDiscounts() : new ArrayList<>();
    }

    public Cart clearDiscounts() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        cart.setDiscounts(new ArrayList<>());
        cart.recalculate();
        return cart;
    }
}
