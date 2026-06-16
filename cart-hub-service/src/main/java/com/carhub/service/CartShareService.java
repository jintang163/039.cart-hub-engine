package com.carhub.service;

import com.carhub.common.constant.CartConstant;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.ProductValidateDTO;
import com.carhub.domain.entity.CartShareEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.mapper.CartShareMapper;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartShareService {

    private final CartShareMapper cartShareMapper;
    private final CartRedisStorage cartRedisStorage;
    private final CartService cartService;
    private final CartValidateService cartValidateService;
    private final CartHubProperties cartHubProperties;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int SHARE_CODE_LENGTH = 8;
    private static final DateTimeFormatter EXPIRE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createShare(String title, Integer expireHours, String password, Integer shareType) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY.getCode(), "购物车为空，无法分享");
        }

        int expireSec = cartHubProperties.getRedis().getShareExpireSeconds();
        if (expireHours != null && expireHours > 0) {
            expireSec = expireHours * 3600;
        }

        String shareId = generateShareId();
        LocalDateTime expireTime = LocalDateTime.now().plusSeconds(expireSec);

        if (shareType == null) {
            shareType = 1;
        }

        CartShareEntity entity = new CartShareEntity();
        entity.setShareId(shareId);
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setOwnerId(userId);
        entity.setTitle(StringUtils.defaultIfBlank(title, "购物车分享"));
        entity.setCartSnapshot(JsonUtil.toJson(cart));
        entity.setItemCount(cart.getItemCount());
        entity.setTotalQuantity(cart.getTotalQuantity());
        entity.setTotalAmount(cart.getTotalAmount());
        entity.setShareType(shareType);
        entity.setViewCount(0);
        entity.setAcceptCount(0);
        entity.setExpireTime(expireTime);

        if (StringUtils.isNotBlank(password)) {
            entity.setPassword(password);
        }

        cartShareMapper.insert(entity);

        String shareKey = RedisKeyConstant.buildCartShareKey(shareId);
        stringRedisTemplate.opsForValue().set(shareKey, JsonUtil.toJson(cart), Duration.ofSeconds(expireSec));

        String shareUrl = buildShareUrl(shareId, tenantId, bizType);
        String qrCodeUrl = buildQrCodeUrl(shareUrl);

        Map<String, Object> result = new HashMap<>();
        result.put("shareId", shareId);
        result.put("shareUrl", shareUrl);
        result.put("qrCodeUrl", qrCodeUrl);
        result.put("expireTime", expireTime.format(EXPIRE_TIME_FORMATTER));
        result.put("needPassword", StringUtils.isNotBlank(password));
        result.put("itemCount", cart.getItemCount());
        result.put("totalQuantity", cart.getTotalQuantity());
        result.put("totalAmount", cart.getTotalAmount());
        result.put("title", entity.getTitle());
        result.put("viewCount", 0);
        result.put("acceptCount", 0);

        log.info("createShare success: tenantId={}, bizType={}, userId={}, shareId={}, shareUrl={}, expireTime={}",
                tenantId, bizType, userId, shareId, shareUrl, expireTime);

        return result;
    }

    private String buildShareUrl(String shareId, String tenantId, String bizType) {
        String baseUrl = cartHubProperties.getShare().getBaseUrl();
        if (StringUtils.isBlank(baseUrl)) {
            baseUrl = "/cart/share";
        }
        StringBuilder sb = new StringBuilder(baseUrl);
        if (baseUrl.contains("?")) {
            sb.append("&");
        } else {
            sb.append("?");
        }
        sb.append("share=").append(shareId);
        if (StringUtils.isNotBlank(tenantId)) {
            sb.append("&tenantId=").append(tenantId);
        }
        if (StringUtils.isNotBlank(bizType)) {
            sb.append("&bizType=").append(bizType);
        }
        return sb.toString();
    }

    private String buildQrCodeUrl(String shareUrl) {
        if (!Boolean.TRUE.equals(cartHubProperties.getShare().getEnableQrCode())) {
            return null;
        }
        String qrCodeApi = cartHubProperties.getShare().getQrCodeApi();
        if (StringUtils.isBlank(qrCodeApi)) {
            return null;
        }
        try {
            String encodedUrl = java.net.URLEncoder.encode(shareUrl, "UTF-8");
            return qrCodeApi + encodedUrl;
        } catch (Exception e) {
            log.warn("build qrcode url failed", e);
            return null;
        }
    }

    public Cart viewShare(String shareId, String password) {
        if (StringUtils.isBlank(shareId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "分享ID不能为空");
        }

        CartShareEntity entity = getShareEntity(shareId);
        checkSharePassword(entity, password);
        checkShareExpired(entity);

        cartShareMapper.incrementViewCount(shareId);

        Cart cart = parseCartSnapshot(entity);
        log.info("viewShare success: shareId={}, viewCount={}", shareId, entity.getViewCount() + 1);

        return cart;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> acceptShare(String shareId, String password) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        if (StringUtils.isBlank(shareId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "分享ID不能为空");
        }

        CartShareEntity entity = getShareEntity(shareId);
        checkSharePassword(entity, password);
        checkShareExpired(entity);

        Cart shareCart = parseCartSnapshot(entity);
        if (shareCart == null || shareCart.getItems() == null || shareCart.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.CART_EMPTY.getCode(), "分享的购物车为空");
        }

        Map<String, Object> validateResult = validateShareItems(tenantId, bizType, shareCart.getItems());
        @SuppressWarnings("unchecked")
        List<CartItem> validItems = (List<CartItem>) validateResult.get("validItems");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invalidItems = (List<Map<String, Object>>) validateResult.get("invalidItems");

        if (validItems.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("mergedCount", 0);
            result.put("invalidItems", invalidItems);
            result.put("message", "所有商品均不可购买");
            return result;
        }

        int mergedCount = 0;
        int mergedQty = 0;
        BigDecimal mergedAmount = BigDecimal.ZERO;

        for (CartItem item : validItems) {
            checkCartLimit(tenantId, bizType, userId);
            checkQuantityLimit(item.getQuantity());

            CartItem mergeItem = buildMergeItem(item);
            boolean merged = cartRedisStorage.mergeUpdateItem(tenantId, bizType, userId, mergeItem, false);
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
            cartShareMapper.incrementAcceptCount(shareId);
            Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
            if (cart != null) {
                cart.recalculate();
                cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("mergedCount", mergedCount);
        result.put("mergedQuantity", mergedQty);
        result.put("mergedAmount", mergedAmount);
        result.put("invalidItems", invalidItems);

        if (!invalidItems.isEmpty()) {
            result.put("message", "成功合并 " + mergedCount + " 件商品，有 " + invalidItems.size() + " 件商品不可购买");
        } else {
            result.put("message", "成功合并 " + mergedCount + " 件商品");
        }

        log.info("acceptShare success: tenantId={}, bizType={}, userId={}, shareId={}, mergedCount={}",
                tenantId, bizType, userId, shareId, mergedCount);

        return result;
    }

    public List<CartShareEntity> listMyShares() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        return cartShareMapper.selectByOwner(tenantId, bizType, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancelShare(String shareId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        if (StringUtils.isBlank(shareId)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "分享ID不能为空");
        }

        CartShareEntity entity = getShareEntity(shareId);

        if (!tenantId.equals(entity.getTenantId())
                || !bizType.equals(entity.getBizType())
                || !userId.equals(entity.getOwnerId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权限取消该分享");
        }

        cartShareMapper.deleteById(entity.getId());

        String shareKey = RedisKeyConstant.buildCartShareKey(shareId);
        stringRedisTemplate.delete(shareKey);

        log.info("cancelShare success: shareId={}, userId={}", shareId, userId);
        return true;
    }

    private String generateShareId() {
        String shareId;
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            shareId = RandomStringUtils.randomAlphanumeric(SHARE_CODE_LENGTH).toUpperCase();
            CartShareEntity exist = cartShareMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CartShareEntity>()
                            .eq("share_id", shareId)
                            .eq("deleted", 0));
            if (exist == null) {
                return shareId;
            }
        }
        throw new BusinessException(ResultCode.ERROR.getCode(), "生成分享码失败，请稍后重试");
    }

    private CartShareEntity getShareEntity(String shareId) {
        CartShareEntity entity = cartShareMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CartShareEntity>()
                        .eq("share_id", shareId)
                        .eq("deleted", 0));
        if (entity == null) {
            Cart cachedCart = cartService.getShareCache(shareId);
            if (cachedCart != null) {
                entity = new CartShareEntity();
                entity.setShareId(shareId);
                entity.setCartSnapshot(JsonUtil.toJson(cachedCart));
                entity.setExpireTime(LocalDateTime.now().plusHours(CartConstant.DEFAULT_SHARE_EXPIRE_HOURS));
                entity.setTenantId(cachedCart.getTenantId());
                entity.setBizType(cachedCart.getBizType());
                entity.setOwnerId(cachedCart.getUserId());
                entity.setItemCount(cachedCart.getItemCount());
                entity.setTotalQuantity(cachedCart.getTotalQuantity());
                entity.setTotalAmount(cachedCart.getTotalAmount());
                return entity;
            }
            throw new BusinessException(ResultCode.CART_SHARE_NOT_FOUND);
        }
        return entity;
    }

    private void checkSharePassword(CartShareEntity entity, String password) {
        if (StringUtils.isNotBlank(entity.getPassword())) {
            if (StringUtils.isBlank(password)) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "请输入访问密码");
            }
            if (!entity.getPassword().equals(password)) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "访问密码错误");
            }
        }
    }

    private void checkShareExpired(CartShareEntity entity) {
        if (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.CART_SHARE_EXPIRED);
        }
    }

    private Cart parseCartSnapshot(CartShareEntity entity) {
        try {
            if (StringUtils.isBlank(entity.getCartSnapshot())) {
                return null;
            }
            return JsonUtil.fromJson(entity.getCartSnapshot(), Cart.class);
        } catch (Exception e) {
            log.error("parse cart snapshot error: shareId={}", entity.getShareId(), e);
            throw new BusinessException(ResultCode.ERROR.getCode(), "分享数据解析失败");
        }
    }

    private Map<String, Object> validateShareItems(String tenantId, String bizType, List<CartItem> items) {
        List<CartItem> validItems = new ArrayList<>();
        List<Map<String, Object>> invalidItems = new ArrayList<>();

        List<ProductValidateDTO> validateList = items.stream()
                .map(item -> {
                    ProductValidateDTO dto = new ProductValidateDTO();
                    dto.setSkuId(item.getSkuId());
                    dto.setSpuId(item.getSpuId());
                    dto.setUnitPrice(item.getUnitPrice());
                    dto.setQuantity(item.getQuantity());
                    return dto;
                })
                .collect(Collectors.toList());

        ValidateResult validateResult = invokeRemoteValidate(tenantId, bizType, validateList);

        if (!validateResult.success) {
            for (CartItem item : items) {
                Map<String, Object> invalidInfo = buildInvalidItemInfo(item, validateResult.errorMessage);
                invalidItems.add(invalidInfo);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("validItems", validItems);
            result.put("invalidItems", invalidItems);
            return result;
        }

        Map<String, CartValidateService.ProductValidateResult> resultMap = new HashMap<>();
        for (CartValidateService.ProductValidateResult r : validateResult.results) {
            resultMap.put(r.getSkuId(), r);
        }

        for (CartItem item : items) {
            CartValidateService.ProductValidateResult r = resultMap.get(item.getSkuId());
            if (r == null) {
                Map<String, Object> invalidInfo = buildInvalidItemInfo(item, "商品信息校验失败");
                invalidItems.add(invalidInfo);
                continue;
            }

            if (Boolean.TRUE.equals(r.getValid()) && Boolean.TRUE.equals(r.getOnShelf())) {
                if (r.getCurrentPrice() != null && item.getUnitPrice() != null
                        && r.getCurrentPrice().compareTo(item.getUnitPrice()) != 0) {
                    item.setOldPrice(item.getUnitPrice());
                    item.setUnitPrice(r.getCurrentPrice());
                    item.setPriceChanged(true);
                }
                if (r.getStock() != null) {
                    item.setStock(r.getStock());
                }
                if (StringUtils.isNotBlank(r.getItemName())) {
                    item.setItemName(r.getItemName());
                }
                if (StringUtils.isNotBlank(r.getItemImage())) {
                    item.setItemImage(r.getItemImage());
                }
                validItems.add(item);
            } else {
                String reason = StringUtils.defaultIfBlank(r.getErrorMessage(), "商品不可购买");
                if (Boolean.FALSE.equals(r.getOnShelf())) {
                    reason = "商品已下架";
                }
                Map<String, Object> invalidInfo = buildInvalidItemInfo(item, reason);
                invalidItems.add(invalidInfo);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("validItems", validItems);
        result.put("invalidItems", invalidItems);
        return result;
    }

    private Map<String, Object> buildInvalidItemInfo(CartItem item, String reason) {
        Map<String, Object> invalidInfo = new HashMap<>();
        invalidInfo.put("skuId", item.getSkuId());
        invalidInfo.put("itemName", item.getItemName());
        invalidInfo.put("itemImage", item.getItemImage());
        invalidInfo.put("quantity", item.getQuantity());
        invalidInfo.put("unitPrice", item.getUnitPrice());
        invalidInfo.put("reason", reason);
        return invalidInfo;
    }

    private static class ValidateResult {
        boolean success;
        String errorMessage;
        List<CartValidateService.ProductValidateResult> results;

        static ValidateResult success(List<CartValidateService.ProductValidateResult> results) {
            ValidateResult r = new ValidateResult();
            r.success = true;
            r.results = results;
            return r;
        }

        static ValidateResult failure(String errorMessage) {
            ValidateResult r = new ValidateResult();
            r.success = false;
            r.errorMessage = errorMessage;
            r.results = Collections.emptyList();
            return r;
        }
    }

    private ValidateResult invokeRemoteValidate(
            String tenantId, String bizType, List<ProductValidateDTO> list) {
        try {
            List<CartValidateService.ProductValidateResult> results =
                    cartValidateService.remoteValidate(tenantId, bizType, list);
            if (results == null || results.isEmpty()) {
                return ValidateResult.failure("商品校验服务未配置或返回空结果，暂不可购买");
            }
            return ValidateResult.success(results);
        } catch (Exception e) {
            log.error("invoke remote validate failed", e);
            return ValidateResult.failure("商品校验服务异常，暂不可购买");
        }
    }

    private CartItem buildMergeItem(CartItem shareItem) {
        return CartItem.builder()
                .skuId(shareItem.getSkuId())
                .spuId(shareItem.getSpuId())
                .categoryId(shareItem.getCategoryId())
                .shopId(shareItem.getShopId())
                .itemName(shareItem.getItemName())
                .itemImage(shareItem.getItemImage())
                .itemSpec(shareItem.getItemSpec())
                .unitPrice(shareItem.getUnitPrice())
                .originalPrice(shareItem.getOriginalPrice())
                .quantity(shareItem.getQuantity())
                .stock(shareItem.getStock())
                .selected(true)
                .addSource(CartContextHolder.getSource())
                .priceChanged(shareItem.getPriceChanged())
                .oldPrice(shareItem.getOldPrice())
                .extInfo(shareItem.getExtInfo())
                .build();
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

}
