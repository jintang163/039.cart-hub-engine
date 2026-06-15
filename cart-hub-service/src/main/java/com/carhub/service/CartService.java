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
    private final CartStatisticsService cartStatisticsService;
    private final CartHubProperties cartHubProperties;
    private final RedissonClient redissonClient;
    private final CartPromotionService cartPromotionService;
    private final CartRecommendService cartRecommendService;
    private final SensitiveWordFilterService sensitiveWordFilterService;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Transactional(rollbackFor = Exception.class)
    public boolean addItem(AddCartItemDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);
        checkCartLimit(tenantId, bizType, userId);

        String processedRemark = processRemark(dto.getRemark());

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
                .remark(processedRemark)
                .extInfo(dto.getExtInfo())
                .build();

        boolean added = cartRedisStorage.addItem(tenantId, bizType, userId, item);
        if (!added) {
            throw new BusinessException(ResultCode.CART_ITEM_INVALID.getCode(), "商品已在购物车中，请使用修改接口调整数量");
        }

        cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_ADD,
                dto.getSkuId(), null, dto.getQuantity(), null, dto.getUnitPrice(), null);

        BigDecimal itemAmount = dto.getUnitPrice() != null
                ? dto.getUnitPrice().multiply(BigDecimal.valueOf(dto.getQuantity()))
                : BigDecimal.ZERO;
        cartStatisticsService.recordAdd(tenantId, bizType, userId, dto.getQuantity(), itemAmount);

        asyncSyncDb(tenantId, bizType, userId);
        recalculateDiscountIfNeeded(tenantId, bizType, userId);
        cartRecommendService.recordAddForRecommend(tenantId, bizType, userId, dto.getSkuId());
        log.info("addItem success: tenantId={}, bizType={}, userId={}, skuId={}, qty={}, hasRemark={}",
                tenantId, bizType, userId, dto.getSkuId(), dto.getQuantity(), StringUtils.isNotBlank(processedRemark));
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

        String processedRemark = processRemark(dto.getRemark());

        CartItem updateItem = CartItem.builder()
                .skuId(dto.getSkuId())
                .quantity(dto.getQuantity())
                .unitPrice(dto.getUnitPrice())
                .selected(dto.getSelected())
                .itemName(dto.getItemName())
                .itemImage(dto.getItemImage())
                .itemSpec(dto.getItemSpec())
                .remark(processedRemark)
                .extInfo(dto.getExtInfo())
                .build();

        boolean updated = cartRedisStorage.updateItem(tenantId, bizType, userId, updateItem);
        if (updated) {
            cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_UPDATE,
                    dto.getSkuId(), oldItem.getQuantity(), dto.getQuantity(),
                    oldItem.getUnitPrice(), dto.getUnitPrice(), null);
            cartStatisticsService.recordUpdate(tenantId, bizType, userId);
            asyncSyncDb(tenantId, bizType, userId);
            recalculateDiscountIfNeeded(tenantId, bizType, userId);
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
            cartStatisticsService.recordUpdate(tenantId, bizType, userId);
            asyncSyncDb(tenantId, bizType, userId);
            recalculateDiscountIfNeeded(tenantId, bizType, userId);
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
            cartStatisticsService.recordDelete(tenantId, bizType, userId);
            asyncSyncDb(tenantId, bizType, userId);
            recalculateDiscountIfNeeded(tenantId, bizType, userId);
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
            cartStatisticsService.recordDelete(tenantId, bizType, userId);
            asyncSyncDb(tenantId, bizType, userId);
            recalculateDiscountIfNeeded(tenantId, bizType, userId);
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
            cartStatisticsService.recordClear(tenantId, bizType, userId);
            asyncSyncDb(tenantId, bizType, userId);
            recalculateDiscountIfNeeded(tenantId, bizType, userId);
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
    public boolean setItemRemark(String skuId, String remark) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        CartItem item = cartRedisStorage.getItem(tenantId, bizType, userId, skuId);
        if (item == null) {
            throw new BusinessException(ResultCode.CART_ITEM_NOT_FOUND);
        }

        String processedRemark = processRemark(remark);

        CartItem updateItem = CartItem.builder()
                .skuId(skuId)
                .remark(processedRemark)
                .build();
        boolean updated = cartRedisStorage.updateItem(tenantId, bizType, userId, updateItem);
        if (updated) {
            cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_UPDATE,
                    skuId, null, null, null, null, "update remark");
            asyncSyncDb(tenantId, bizType, userId);
        }
        return updated;
    }

    public String getItemRemark(String skuId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);
        CartItem item = cartRedisStorage.getItem(tenantId, bizType, userId, skuId);
        return item != null ? item.getRemark() : null;
    }

    public Map<String, String> getAllItemRemarks() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);
        List<CartItem> items = cartRedisStorage.getItems(tenantId, bizType, userId);
        Map<String, String> remarks = new LinkedHashMap<>();
        if (items != null) {
            for (CartItem item : items) {
                if (item.getRemark() != null) {
                    remarks.put(item.getSkuId(), item.getRemark());
                }
            }
        }
        return remarks;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean removeItemRemark(String skuId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);

        CartItem updateItem = CartItem.builder()
                .skuId(skuId)
                .remark("")
                .build();
        return cartRedisStorage.updateItem(tenantId, bizType, userId, updateItem);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean clearAllItemRemarks() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();
        validateUserId(userId);
        List<CartItem> items = cartRedisStorage.getItems(tenantId, bizType, userId);
        if (items == null || items.isEmpty()) {
            return true;
        }
        boolean allSuccess = true;
        for (CartItem item : items) {
            if (StringUtils.isNotBlank(item.getRemark())) {
                CartItem updateItem = CartItem.builder()
                        .skuId(item.getSkuId())
                        .remark("")
                        .build();
                if (!cartRedisStorage.updateItem(tenantId, bizType, userId, updateItem)) {
                    allSuccess = false;
                }
            }
        }
        return allSuccess;
    }

    private String processRemark(String remark) {
        if (!Boolean.TRUE.equals(cartHubProperties.getRemark().getEnable())) {
            return null;
        }
        if (StringUtils.isBlank(remark)) {
            return remark;
        }
        Integer maxLength = cartHubProperties.getRemark().getMaxLength();
        if (maxLength != null && remark.length() > maxLength) {
            throw new BusinessException(ResultCode.REMARK_TOO_LONG.getCode(),
                    "商品备注过长，最大允许" + maxLength + "字符，当前" + remark.length() + "字符");
        }
        if (sensitiveWordFilterService.containsSensitiveWord(remark)) {
            List<String> foundWords = sensitiveWordFilterService.findSensitiveWords(remark);
            throw new BusinessException(ResultCode.REMARK_CONTAIN_SENSITIVE_WORD.getCode(),
                    "商品备注包含敏感词：" + String.join("、", foundWords));
        }
        return remark;
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
        int mergedQty = 0;
        BigDecimal mergedAmount = BigDecimal.ZERO;
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
                mergedQty += item.getQuantity() != null ? item.getQuantity() : 0;
                if (item.getUnitPrice() != null && item.getQuantity() != null) {
                    mergedAmount = mergedAmount.add(
                            item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
                }
            }
        }

        if (mergedCount > 0) {
            cartStatisticsService.recordAdd(tenantId, bizType, userId, mergedQty, mergedAmount);
        }

        cartHistoryService.recordHistory(tenantId, bizType, userId, CartConstant.ACTION_MERGE,
                null, null, null, null, null, "merged " + mergedCount + " items");

        asyncSyncDb(tenantId, bizType, userId);
        recalculateDiscountIfNeeded(tenantId, bizType, userId);
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
                .validateEnabled(cartHubProperties.getValidate().getEnable() && validate)
                .selectedCouponId(cart.getSelectedCouponId())
                .selectedPromotionIds(cart.getSelectedPromotionIds())
                .couponCode(cart.getCouponCode())
                .discountDetails(cart.getDiscountDetails())
                .gifts(cart.getGifts())
                .discountCalculated(cart.getDiscountCalculated())
                .discountCalculateTime(cart.getDiscountCalculateTime());

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

    private void recalculateDiscountIfNeeded(String tenantId, String bizType, String userId) {
        try {
            if (cartHubProperties.getPromotion() != null && Boolean.TRUE.equals(cartHubProperties.getPromotion().getEnable())) {
                Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
                if (cart != null
                        && (StringUtils.isNotBlank(cart.getSelectedCouponId())
                        || (cart.getSelectedPromotionIds() != null && !cart.getSelectedPromotionIds().isEmpty()))) {
                    cartPromotionService.recalculateDiscount(cart);
                    cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
                }
            }
        } catch (Exception e) {
            log.warn("Auto recalculate discount failed: {}", e.getMessage());
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
