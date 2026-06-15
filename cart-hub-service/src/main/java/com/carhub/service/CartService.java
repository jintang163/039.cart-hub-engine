package com.carhub.service;

import com.carhub.common.constant.CartConstant;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.*;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.CartVO;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRedisStorage cartRedisStorage;
    private final CartValidateService cartValidateService;
    private final CartHistoryService cartHistoryService;
    private final CartDbSyncService cartDbSyncService;
    private final CartHubProperties cartHubProperties;
    private final RedissonClient redissonClient;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Transactional(rollbackFor = Exception.class)
    public boolean addItem(AddCartItemDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);
        checkCartLimit(tenantId, bizType, userId);

        CartItem item = CartItem.builder()
                .skuId(dto.getSkuId())
                .spuId(dto.getSpuId())
                .categoryId(dto.getCategoryId())
                .shopId(dto.getShopId())
                .itemName(dto.getItemName())
                .itemImage(dto.getItemImage())
                .itemSpec(dto.getItemSpec())
                .unitPrice(dto.getUnitPrice())
                .originalPrice(dto.getOriginalPrice())
                .quantity(dto.getQuantity())
                .stock(dto.getStock())
                .selected(dto.getSelected())
                .addSource(StringUtils.defaultIfBlank(dto.getAddSource(), CartContextHolder.getSource()))
                .extInfo(dto.getExtInfo())
                .build();

        boolean added = cartRedisStorage.addItem(tenantId, bizType, userId, item);
        if (!added) {
            throw new BusinessException(ResultCode.CART_ITEM_INVALID.getCode(), "商品已在购物车中，请使用修改接口调整数量");
        }

        cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_ADD,
                dto.getSkuId(), null, dto.getQuantity(), null, dto.getUnitPrice(), null);

        asyncSyncDb(tenantId, bizType, userId);
        log.info("addItem success: tenantId={}, bizType={}, userId={}, skuId={}, qty={}",
                tenantId, bizType, userId, dto.getSkuId(), dto.getQuantity());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean updateItem(UpdateCartItemDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        CartItem oldItem = cartRedisStorage.getItem(tenantId, bizType, userId, dto.getSkuId());
        if (oldItem == null) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_FOUND);
        }

        checkQuantityLimit(dto.getQuantity());

        CartItem updateItem = CartItem.builder()
                .skuId(dto.getSkuId())
                .quantity(dto.getQuantity())
                .unitPrice(dto.getUnitPrice())
                .selected(dto.getSelected())
                .itemName(dto.getItemName())
                .itemImage(dto.getItemImage())
                .itemSpec(dto.getItemSpec())
                .extInfo(dto.getExtInfo())
                .build();

        boolean updated = cartRedisStorage.updateItem(tenantId, bizType, userId, updateItem);
        if (updated) {
            cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_UPDATE,
                    dto.getSkuId(), oldItem.getQuantity(), dto.getQuantity(),
                    oldItem.getUnitPrice(), dto.getUnitPrice(), null);
            asyncSyncDb(tenantId, bizType, userId);
        }
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long incrementQuantity(String skuId, int delta) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        CartItem oldItem = cartRedisStorage.getItem(tenantId, bizType, userId, skuId);
        if (oldItem == null) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_FOUND);
        }

        Long newQty = cartRedisStorage.incrementQuantity(tenantId, bizType, userId, skuId, delta);
        if (newQty != null) {
            cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_UPDATE,
                    skuId, oldItem.getQuantity(), newQty.intValue(), null, null, null);
            asyncSyncDb(tenantId, bizType, userId);
        }
        return newQty;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean removeItem(String skuId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        CartItem oldItem = cartRedisStorage.getItem(tenantId, bizType, userId, skuId);
        if (oldItem == null) {
            return true;
        }

        boolean removed = cartRedisStorage.removeItem(tenantId, bizType, userId, skuId);
        if (removed) {
            cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_DELETE,
                    skuId, oldItem.getQuantity(), 0, null, null, null);
            asyncSyncDb(tenantId, bizType, userId);
        }
        return removed;
    }

    @Transactional(rollbackFor = Exception.class)
    public long batchRemove(BatchCartItemDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        long count = cartRedisStorage.batchRemove(tenantId, bizType, userId, dto.getSkuIds());
        if (count > 0) {
            for (String skuId : dto.getSkuIds()) {
                cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_DELETE,
                        skuId, null, 0, null, null, "batchRemove");
            }
            asyncSyncDb(tenantId, bizType, userId);
        }
        return count;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean clearCart() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_CLEAR,
                null, null, null, null, null, null);
        boolean result = cartRedisStorage.clearCart(tenantId, bizType, userId);
        if (result) {
            asyncSyncDb(tenantId, bizType, userId);
        }
        return result;
    }

    public CartVO getCart(boolean validate) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);

        if (validate && cartHubProperties.getValidate().getEnable()
                && cart.getItems() != null && !cart.getItems().isEmpty()) {
            cartValidateService.validateAndRecalculate(tenantId, bizType, userId, cart);
        }

        return buildCartVO(cart, validate);
    }

    public CartVO getCartSimple() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        return buildCartVO(cart, false);
    }

    public Integer getItemCount() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);
        return cartRedisStorage.getItemCount(tenantId, bizType, userId);
    }

    public Map<String, Object> getCartSummary() {
        CartVO cartVO = getCartSimple();
        Map<String, Object> summary = new HashMap<>();
        summary.put("itemCount", cartVO.getItemCount());
        summary.put("totalQuantity", cartVO.getTotalQuantity());
        summary.put("totalAmount", cartVO.getTotalAmount());
        summary.put("discountAmount", cartVO.getDiscountAmount());
        summary.put("payAmount", cartVO.getPayAmount());
        summary.put("hasPriceChanged", cartVO.getHasPriceChanged());
        summary.put("hasInvalidItem", cartVO.getHasInvalidItem());
        return summary;
    }

    @Transactional(rollbackFor = Exception.class)
    public CartVO mergeCart(MergeCartDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            return getCartSimple();
        }

        int mergedCount = 0;
        for (CartItem item : dto.getItems()) {
            if (item == null || StringUtils.isBlank(item.getSkuId())) {
                continue;
            }
            checkCartLimit(tenantId, bizType, userId);
            checkQuantityLimit(item.getQuantity());
            boolean merged = cartRedisStorage.mergeUpdateItem(tenantId, bizType, userId, item,
                    Boolean.TRUE.equals(dto.getOverwrite()));
            if (merged) {
                mergedCount++;
            }
        }

        cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_MERGE,
                null, null, null, null, null, "merged " + mergedCount + " items");

        asyncSyncDb(tenantId, bizType, userId);
        log.info("mergeCart success: tenantId={}, bizType={}, userId={}, mergedCount={}",
                tenantId, bizType, userId, mergedCount);
        return getCartSimple();
    }

    public void recalculateForSku(String tenantId, String bizType, String skuId) {
        if (StringUtils.isAnyBlank(tenantId, bizType, skuId)) {
            return;
        }
        Set<String> userIds = cartRedisStorage.searchUsersWithSku(tenantId, bizType, skuId, 1000);
        log.info("recalculateForSku: tenantId={}, bizType={}, skuId={}, affectedUsers={}",
                tenantId, bizType, skuId, userIds.size());
        for (String userId : userIds) {
            try {
                Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
                if (cart != null && cart.getItems() != null) {
                    cartValidateService.revalidateSingleSku(tenantId, bizType, userId, cart, skuId);
                }
            } catch (Exception e) {
                log.error("recalculate cart error, userId={}, skuId={}", userId, skuId, e);
            }
        }
    }

    private CartVO buildCartVO(Cart cart, boolean validate) {
        CartVO.CartVOBuilder builder = CartVO.builder()
                .tenantId(cart.getTenantId())
                .bizType(cart.getBizType())
                .userId(cart.getUserId())
                .items(cart.getItems())
                .itemCount(cart.getItemCount())
                .totalQuantity(cart.getTotalQuantity())
                .totalAmount(cart.getTotalAmount())
                .discountAmount(cart.getDiscountAmount())
                .payAmount(cart.getPayAmount())
                .hasPriceChanged(cart.getHasPriceChanged())
                .hasInvalidItem(cart.getHasInvalidItem())
                .discounts(cart.getDiscounts())
                .version(cart.getVersion())
                .updateTime(cart.getUpdateTime())
                .validateEnabled(cartHubProperties.getValidate().getEnable() && validate);

        if (cart.getItems() != null && !cart.getItems().isEmpty()) {
            List<CartItem> validItems = cart.getItems().stream()
                    .filter(i -> Boolean.TRUE.equals(i.getOnShelf())
                            && (i.getInvalidMessage() == null || i.getInvalidMessage().isEmpty()))
                    .collect(Collectors.toList());
            List<CartItem> invalidItems = cart.getItems().stream()
                    .filter(i -> Boolean.FALSE.equals(i.getOnShelf())
                            || (i.getInvalidMessage() != null && !i.getInvalidMessage().isEmpty()))
                    .collect(Collectors.toList());
            builder.validItems(validItems);
            builder.invalidItems(invalidItems);
            builder.validItemCount(validItems.size());
        } else {
            builder.validItems(new ArrayList<>());
            builder.invalidItems(new ArrayList<>());
            builder.validItemCount(0);
        }

        return builder.build();
    }

    private void validateUserId(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }
    }

    private void checkCartLimit(String tenantId, String bizType, String userId) {
        int currentSize = cartRedisStorage.getItemCount(tenantId, bizType, userId);
        int maxSize = cartHubProperties.getLimit().getMaxCartSize();
        if (currentSize >= maxSize) {
            throw new BusinessException("购物车商品数量已达上限(" + maxSize + ")，请先清理部分商品");
        }
    }

    private void checkQuantityLimit(Integer quantity) {
        if (quantity == null) {
            return;
        }
        int maxQty = cartHubProperties.getLimit().getMaxItemQuantity();
        if (quantity > maxQty) {
            throw new BusinessException("单商品数量超过上限(" + maxQty + ")");
        }
        if (quantity < 0) {
            throw new BusinessException(ResultCode.CART_QUANTITY_INVALID);
        }
    }

    private void asyncSyncDb(String tenantId, String bizType, String userId) {
        if (cartHubProperties.getSync().getEnableDbSync()) {
            cartDbSyncService.markNeedSync(tenantId, bizType, userId);
        }
    }

    public void saveShareCache(String shareId, Cart cart) {
        String key = RedisKeyConstant.buildCartShareKey(shareId);
        stringRedisTemplate.opsForValue().set(key, JsonUtil.toJson(cart),
                Duration.ofSeconds(cartHubProperties.getRedis().getShareExpireSeconds()));
    }

    public Cart getShareCache(String shareId) {
        String key = RedisKeyConstant.buildCartShareKey(shareId);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JsonUtil.fromJson(json, Cart.class);
    }

}
