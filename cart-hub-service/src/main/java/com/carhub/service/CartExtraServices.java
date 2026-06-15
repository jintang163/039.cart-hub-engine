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
import com.carhub.domain.dto.PromotionCalculateDTO;
import com.carhub.domain.entity.*;
import com.carhub.domain.model.*;
import com.carhub.domain.vo.CouponVO;
import com.carhub.domain.vo.DiscountResultVO;
import com.carhub.domain.vo.PromotionVO;
import com.carhub.mapper.*;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

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
        cart.setSelectedCouponId(null);
        cart.setSelectedPromotionIds(null);
        cart.setCouponCode(null);
        cart.setDiscountDetails(null);
        cart.setGifts(null);
        cart.setDiscountCalculated(false);
        cart.recalculate();
        return cart;
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class PromotionEngineService {

    private final CartHubProperties cartHubProperties;
    private final CouponTemplateMapper couponTemplateMapper;
    private final PromotionActivityMapper promotionActivityMapper;
    private final UserCouponMapper userCouponMapper;
    private final CartRedisStorage cartRedisStorage;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    public DiscountResultVO calculateDiscount(PromotionCalculateDTO dto) {
        if (!cartHubProperties.getPromotion().getEnable()) {
            return buildLocalCalculateResult(dto);
        }

        String calculateUrl = cartHubProperties.getPromotion().getCalculateUrl();
        if (StringUtils.isBlank(calculateUrl)) {
            return buildLocalCalculateResult(dto);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<PromotionCalculateDTO> request = new HttpEntity<>(dto, headers);

            ResponseEntity<DiscountResultVO> response = restTemplate.exchange(
                    calculateUrl,
                    HttpMethod.POST,
                    request,
                    DiscountResultVO.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            log.warn("Call promotion calculate interface failed, use local calculate instead: {}", e.getMessage());
        }

        return buildLocalCalculateResult(dto);
    }

    private DiscountResultVO buildLocalCalculateResult(PromotionCalculateDTO dto) {
        DiscountResultVO.DiscountResultVOBuilder resultBuilder = DiscountResultVO.builder()
                .totalAmount(dto.getTotalAmount())
                .discountAmount(BigDecimal.ZERO)
                .payAmount(dto.getTotalAmount())
                .success(true);

        List<CartDiscount> discounts = new ArrayList<>();
        List<DiscountDetail> details = new ArrayList<>();
        List<GiftItem> gifts = new ArrayList<>();
        BigDecimal totalDiscount = BigDecimal.ZERO;

        if (StringUtils.isNotBlank(dto.getSelectedCouponId())) {
            CartDiscount couponDiscount = calculateCouponDiscount(dto);
            if (couponDiscount != null && Boolean.TRUE.equals(couponDiscount.getEnable())) {
                discounts.add(couponDiscount);
                totalDiscount = totalDiscount.add(couponDiscount.getDiscountAmount());
                if (couponDiscount.getDetails() != null) {
                    details.addAll(couponDiscount.getDetails());
                }
                if (couponDiscount.getGifts() != null) {
                    gifts.addAll(couponDiscount.getGifts());
                }
            }
        }

        if (dto.getSelectedPromotionIds() != null && !dto.getSelectedPromotionIds().isEmpty()) {
            for (String promotionId : dto.getSelectedPromotionIds()) {
                CartDiscount promotionDiscount = calculatePromotionDiscount(dto, promotionId);
                if (promotionDiscount != null && Boolean.TRUE.equals(promotionDiscount.getEnable())) {
                    discounts.add(promotionDiscount);
                    totalDiscount = totalDiscount.add(promotionDiscount.getDiscountAmount());
                    if (promotionDiscount.getDetails() != null) {
                        details.addAll(promotionDiscount.getDetails());
                    }
                    if (promotionDiscount.getGifts() != null) {
                        gifts.addAll(promotionDiscount.getGifts());
                    }
                }
            }
        }

        BigDecimal payAmount = dto.getTotalAmount().subtract(totalDiscount);
        if (payAmount.compareTo(BigDecimal.ZERO) < 0) {
            payAmount = BigDecimal.ZERO;
        }

        resultBuilder.discounts(discounts)
                .discountDetails(details)
                .gifts(gifts)
                .discountAmount(totalDiscount)
                .payAmount(payAmount);

        return resultBuilder.build();
    }

    private CartDiscount calculateCouponDiscount(PromotionCalculateDTO dto) {
        String couponId = dto.getSelectedCouponId();
        String couponCode = dto.getCouponCode();

        UserCouponEntity userCoupon = null;
        if (StringUtils.isNotBlank(couponId)) {
            LambdaQueryWrapper<UserCouponEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserCouponEntity::getTenantId, dto.getTenantId())
                    .eq(UserCouponEntity::getBizType, dto.getBizType())
                    .eq(UserCouponEntity::getUserId, dto.getUserId())
                    .eq(UserCouponEntity::getCouponId, couponId)
                    .eq(UserCouponEntity::getStatus, 1)
                    .eq(UserCouponEntity::getDeleted, 0);
            userCoupon = userCouponMapper.selectOne(wrapper);
        } else if (StringUtils.isNotBlank(couponCode)) {
            LambdaQueryWrapper<UserCouponEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(UserCouponEntity::getTenantId, dto.getTenantId())
                    .eq(UserCouponEntity::getBizType, dto.getBizType())
                    .eq(UserCouponEntity::getUserId, dto.getUserId())
                    .eq(UserCouponEntity::getCouponCode, couponCode)
                    .eq(UserCouponEntity::getStatus, 1)
                    .eq(UserCouponEntity::getDeleted, 0);
            userCoupon = userCouponMapper.selectOne(wrapper);
        }

        if (userCoupon == null) {
            return CartDiscount.builder()
                    .discountId(couponId != null ? couponId : couponCode)
                    .discountType("coupon")
                    .discountName("优惠券")
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("优惠券不存在或已使用")
                    .build();
        }

        LocalDateTime now = LocalDateTime.now();
        if (userCoupon.getEndTime() != null && now.isAfter(userCoupon.getEndTime())) {
            return CartDiscount.builder()
                    .discountId(userCoupon.getCouponId())
                    .discountType("coupon")
                    .discountName(userCoupon.getCouponName())
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("优惠券已过期")
                    .build();
        }

        if (userCoupon.getStartTime() != null && now.isBefore(userCoupon.getStartTime())) {
            return CartDiscount.builder()
                    .discountId(userCoupon.getCouponId())
                    .discountType("coupon")
                    .discountName(userCoupon.getCouponName())
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("优惠券尚未开始")
                    .build();
        }

        if (dto.getTotalAmount().compareTo(userCoupon.getThresholdAmount()) < 0) {
            return CartDiscount.builder()
                    .discountId(userCoupon.getCouponId())
                    .discountType("coupon")
                    .discountName(userCoupon.getCouponName())
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("未达到使用门槛，还差 " + userCoupon.getThresholdAmount().subtract(dto.getTotalAmount()) + " 元")
                    .build();
        }

        BigDecimal discountAmount = calculateDiscountAmount(
                userCoupon.getCouponType(),
                dto.getTotalAmount(),
                userCoupon.getDiscountAmount(),
                userCoupon.getDiscountPercent(),
                userCoupon.getMaxDiscountAmount()
        );

        List<DiscountDetail> detailList = new ArrayList<>();
        DiscountDetail detail = DiscountDetail.builder()
                .discountId(userCoupon.getCouponId())
                .discountType("coupon")
                .discountName(userCoupon.getCouponName())
                .discountCode(userCoupon.getCouponCode())
                .discountAmount(discountAmount)
                .promotionType(userCoupon.getPromotionType())
                .thresholdAmount(userCoupon.getThresholdAmount())
                .discountValue(userCoupon.getDiscountAmount())
                .discountPercent(userCoupon.getDiscountPercent())
                .maxDiscountAmount(userCoupon.getMaxDiscountAmount())
                .build();
        detailList.add(detail);

        return CartDiscount.builder()
                .discountId(userCoupon.getCouponId())
                .discountType("coupon")
                .discountName(userCoupon.getCouponName())
                .discountCode(userCoupon.getCouponCode())
                .discountAmount(discountAmount)
                .promotionType(userCoupon.getPromotionType())
                .thresholdAmount(userCoupon.getThresholdAmount())
                .discountValue(userCoupon.getDiscountAmount())
                .discountPercent(userCoupon.getDiscountPercent())
                .maxDiscountAmount(userCoupon.getMaxDiscountAmount())
                .startTime(userCoupon.getStartTime())
                .endTime(userCoupon.getEndTime())
                .enable(true)
                .details(detailList)
                .build();
    }

    private CartDiscount calculatePromotionDiscount(PromotionCalculateDTO dto, String promotionId) {
        LambdaQueryWrapper<PromotionActivityEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromotionActivityEntity::getTenantId, dto.getTenantId())
                .eq(PromotionActivityEntity::getBizType, dto.getBizType())
                .eq(PromotionActivityEntity::getPromotionId, promotionId)
                .eq(PromotionActivityEntity::getStatus, 1)
                .eq(PromotionActivityEntity::getDeleted, 0);
        PromotionActivityEntity promotion = promotionActivityMapper.selectOne(wrapper);

        if (promotion == null) {
            return CartDiscount.builder()
                    .discountId(promotionId)
                    .discountType("promotion")
                    .discountName("促销活动")
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("促销活动不存在")
                    .build();
        }

        LocalDateTime now = LocalDateTime.now();
        if (promotion.getEndTime() != null && now.isAfter(promotion.getEndTime())) {
            return CartDiscount.builder()
                    .discountId(promotion.getPromotionId())
                    .discountType("promotion")
                    .discountName(promotion.getPromotionName())
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("促销活动已结束")
                    .build();
        }

        if (promotion.getStartTime() != null && now.isBefore(promotion.getStartTime())) {
            return CartDiscount.builder()
                    .discountId(promotion.getPromotionId())
                    .discountType("promotion")
                    .discountName(promotion.getPromotionName())
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("促销活动尚未开始")
                    .build();
        }

        if (dto.getTotalAmount().compareTo(promotion.getThresholdAmount()) < 0) {
            return CartDiscount.builder()
                    .discountId(promotion.getPromotionId())
                    .discountType("promotion")
                    .discountName(promotion.getPromotionName())
                    .discountAmount(BigDecimal.ZERO)
                    .enable(false)
                    .errorMessage("未达到活动门槛，还差 " + promotion.getThresholdAmount().subtract(dto.getTotalAmount()) + " 元")
                    .build();
        }

        BigDecimal discountAmount = calculateDiscountAmount(
                promotion.getPromotionType(),
                dto.getTotalAmount(),
                promotion.getDiscountAmount(),
                promotion.getDiscountPercent(),
                promotion.getMaxDiscountAmount()
        );

        List<DiscountDetail> detailList = new ArrayList<>();
        DiscountDetail detail = DiscountDetail.builder()
                .discountId(promotion.getPromotionId())
                .discountType("promotion")
                .discountName(promotion.getPromotionName())
                .discountAmount(discountAmount)
                .promotionType(promotion.getPromotionType())
                .promotionId(promotion.getPromotionId())
                .promotionName(promotion.getPromotionName())
                .thresholdAmount(promotion.getThresholdAmount())
                .discountValue(promotion.getDiscountAmount())
                .discountPercent(promotion.getDiscountPercent())
                .maxDiscountAmount(promotion.getMaxDiscountAmount())
                .build();
        detailList.add(detail);

        List<GiftItem> giftItems = parseGiftInfo(promotion.getGiftInfo());

        return CartDiscount.builder()
                .discountId(promotion.getPromotionId())
                .discountType("promotion")
                .discountName(promotion.getPromotionName())
                .discountAmount(discountAmount)
                .promotionType(promotion.getPromotionType())
                .promotionId(promotion.getPromotionId())
                .promotionName(promotion.getPromotionName())
                .thresholdAmount(promotion.getThresholdAmount())
                .discountValue(promotion.getDiscountAmount())
                .discountPercent(promotion.getDiscountPercent())
                .maxDiscountAmount(promotion.getMaxDiscountAmount())
                .startTime(promotion.getStartTime())
                .endTime(promotion.getEndTime())
                .stackable(promotion.getStackable())
                .priority(promotion.getPriority())
                .enable(true)
                .details(detailList)
                .gifts(giftItems)
                .build();
    }

    private BigDecimal calculateDiscountAmount(String promotionType, BigDecimal totalAmount,
                                               BigDecimal discountAmount, Integer discountPercent,
                                               BigDecimal maxDiscountAmount) {
        BigDecimal result = BigDecimal.ZERO;

        switch (promotionType) {
            case "fixed":
            case "full_reduction":
                result = discountAmount != null ? discountAmount : BigDecimal.ZERO;
                break;
            case "percent":
            case "discount":
                if (discountPercent != null && discountPercent > 0) {
                    BigDecimal percent = BigDecimal.valueOf(discountPercent).divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
                    result = totalAmount.multiply(BigDecimal.ONE.subtract(percent));
                }
                break;
            default:
                result = discountAmount != null ? discountAmount : BigDecimal.ZERO;
                break;
        }

        if (maxDiscountAmount != null && result.compareTo(maxDiscountAmount) > 0) {
            result = maxDiscountAmount;
        }

        return result.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    private List<GiftItem> parseGiftInfo(String giftInfo) {
        if (StringUtils.isBlank(giftInfo)) {
            return new ArrayList<>();
        }
        try {
            return JsonUtil.fromJsonList(giftInfo, GiftItem.class);
        } catch (Exception e) {
            log.warn("Parse gift info failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<CouponVO> listAvailableCoupons(String tenantId, String bizType, String userId, BigDecimal totalAmount) {
        String listUrl = cartHubProperties.getPromotion().getCouponListUrl();
        if (StringUtils.isNotBlank(listUrl)) {
            try {
                String url = listUrl + "?tenantId=" + tenantId + "&bizType=" + bizType + "&userId=" + userId;
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    return JsonUtil.fromJsonList(response.getBody(), CouponVO.class);
                }
            } catch (Exception e) {
                log.warn("Call coupon list interface failed, use local data instead: {}", e.getMessage());
            }
        }

        LambdaQueryWrapper<UserCouponEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCouponEntity::getTenantId, tenantId)
                .eq(UserCouponEntity::getBizType, bizType)
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getStatus, 1)
                .eq(UserCouponEntity::getDeleted, 0)
                .ge(UserCouponEntity::getEndTime, LocalDateTime.now())
                .orderByAsc(UserCouponEntity::getEndTime);

        List<UserCouponEntity> userCoupons = userCouponMapper.selectList(wrapper);

        return userCoupons.stream().map(uc -> {
            boolean available = true;
            String reason = null;

            if (totalAmount != null && totalAmount.compareTo(uc.getThresholdAmount()) < 0) {
                available = false;
                reason = "未达到使用门槛，还差 " + uc.getThresholdAmount().subtract(totalAmount) + " 元";
            }

            return CouponVO.builder()
                    .couponId(uc.getCouponId())
                    .couponName(uc.getCouponName())
                    .couponType(uc.getCouponType())
                    .promotionType(uc.getPromotionType())
                    .thresholdAmount(uc.getThresholdAmount())
                    .discountAmount(uc.getDiscountAmount())
                    .discountPercent(uc.getDiscountPercent())
                    .maxDiscountAmount(uc.getMaxDiscountAmount())
                    .startTime(uc.getStartTime())
                    .endTime(uc.getEndTime())
                    .available(available)
                    .unavailableReason(reason)
                    .build();
        }).collect(Collectors.toList());
    }

    public List<PromotionVO> listAvailablePromotions(String tenantId, String bizType, BigDecimal totalAmount) {
        String listUrl = cartHubProperties.getPromotion().getPromotionListUrl();
        if (StringUtils.isNotBlank(listUrl)) {
            try {
                String url = listUrl + "?tenantId=" + tenantId + "&bizType=" + bizType;
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    return JsonUtil.fromJsonList(response.getBody(), PromotionVO.class);
                }
            } catch (Exception e) {
                log.warn("Call promotion list interface failed, use local data instead: {}", e.getMessage());
            }
        }

        LambdaQueryWrapper<PromotionActivityEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromotionActivityEntity::getTenantId, tenantId)
                .eq(PromotionActivityEntity::getBizType, bizType)
                .eq(PromotionActivityEntity::getStatus, 1)
                .eq(PromotionActivityEntity::getDeleted, 0)
                .le(PromotionActivityEntity::getStartTime, LocalDateTime.now())
                .ge(PromotionActivityEntity::getEndTime, LocalDateTime.now())
                .orderByAsc(PromotionActivityEntity::getPriority);

        List<PromotionActivityEntity> promotions = promotionActivityMapper.selectList(wrapper);

        return promotions.stream().map(p -> {
            boolean available = true;
            String reason = null;

            if (totalAmount != null && totalAmount.compareTo(p.getThresholdAmount()) < 0) {
                available = false;
                reason = "未达到活动门槛，还差 " + p.getThresholdAmount().subtract(totalAmount) + " 元";
            }

            List<PromotionVO.GiftItemVO> giftVOs = null;
            List<GiftItem> gifts = parseGiftInfo(p.getGiftInfo());
            if (gifts != null && !gifts.isEmpty()) {
                giftVOs = gifts.stream().map(g -> PromotionVO.GiftItemVO.builder()
                        .skuId(g.getSkuId())
                        .itemName(g.getItemName())
                        .itemImage(g.getItemImage())
                        .quantity(g.getQuantity())
                        .unitPrice(g.getUnitPrice())
                        .build()).collect(Collectors.toList());
            }

            return PromotionVO.builder()
                    .promotionId(p.getPromotionId())
                    .promotionName(p.getPromotionName())
                    .promotionType(p.getPromotionType())
                    .promotionDesc(p.getPromotionDesc())
                    .thresholdAmount(p.getThresholdAmount())
                    .discountAmount(p.getDiscountAmount())
                    .discountPercent(p.getDiscountPercent())
                    .maxDiscountAmount(p.getMaxDiscountAmount())
                    .startTime(p.getStartTime())
                    .endTime(p.getEndTime())
                    .available(available)
                    .unavailableReason(reason)
                    .stackable(p.getStackable())
                    .priority(p.getPriority())
                    .gifts(giftVOs)
                    .build();
        }).collect(Collectors.toList());
    }
}

@Slf4j
@Service
@RequiredArgsConstructor
class CartPromotionService {

    private final CartRedisStorage cartRedisStorage;
    private final PromotionEngineService promotionEngineService;
    private final UserCouponMapper userCouponMapper;
    private final CartDbSyncService cartDbSyncService;
    private final CartHubProperties cartHubProperties;

    public Cart applyCouponCode(String couponCode) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        LambdaQueryWrapper<UserCouponEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCouponEntity::getTenantId, tenantId)
                .eq(UserCouponEntity::getBizType, bizType)
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getCouponCode, couponCode)
                .eq(UserCouponEntity::getStatus, 1)
                .eq(UserCouponEntity::getDeleted, 0);
        UserCouponEntity userCoupon = userCouponMapper.selectOne(wrapper);

        if (userCoupon == null) {
            throw new BusinessException("优惠券码无效或已被使用");
        }

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        cart.setSelectedCouponId(userCoupon.getCouponId());
        cart.setCouponCode(couponCode);

        cart = recalculateDiscount(cart);
        cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
        asyncSyncDb(tenantId, bizType, userId);
        return cart;
    }

    public Cart applyCoupon(String couponId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        LambdaQueryWrapper<UserCouponEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserCouponEntity::getTenantId, tenantId)
                .eq(UserCouponEntity::getBizType, bizType)
                .eq(UserCouponEntity::getUserId, userId)
                .eq(UserCouponEntity::getCouponId, couponId)
                .eq(UserCouponEntity::getStatus, 1)
                .eq(UserCouponEntity::getDeleted, 0);
        UserCouponEntity userCoupon = userCouponMapper.selectOne(wrapper);

        if (userCoupon == null) {
            throw new BusinessException("优惠券不存在或已被使用");
        }

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        cart.setSelectedCouponId(couponId);
        cart.setCouponCode(userCoupon.getCouponCode());

        cart = recalculateDiscount(cart);
        cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
        asyncSyncDb(tenantId, bizType, userId);
        return cart;
    }

    public Cart applyPromotion(String promotionId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart.getSelectedPromotionIds() == null) {
            cart.setSelectedPromotionIds(new ArrayList<>());
        }
        if (!cart.getSelectedPromotionIds().contains(promotionId)) {
            cart.getSelectedPromotionIds().add(promotionId);
        }

        cart = recalculateDiscount(cart);
        cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
        asyncSyncDb(tenantId, bizType, userId);
        return cart;
    }

    public Cart removeCoupon() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        cart.setSelectedCouponId(null);
        cart.setCouponCode(null);

        cart = recalculateDiscount(cart);
        cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
        asyncSyncDb(tenantId, bizType, userId);
        return cart;
    }

    public Cart removePromotion(String promotionId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart.getSelectedPromotionIds() != null) {
            cart.getSelectedPromotionIds().remove(promotionId);
        }

        cart = recalculateDiscount(cart);
        cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
        asyncSyncDb(tenantId, bizType, userId);
        return cart;
    }

    public Cart recalculateDiscount(Cart cart) {
        PromotionCalculateDTO calculateDTO = PromotionCalculateDTO.builder()
                .tenantId(cart.getTenantId())
                .bizType(cart.getBizType())
                .userId(cart.getUserId())
                .items(cart.getItems())
                .totalAmount(cart.getTotalAmount())
                .selectedCouponId(cart.getSelectedCouponId())
                .couponCode(cart.getCouponCode())
                .selectedPromotionIds(cart.getSelectedPromotionIds())
                .build();

        DiscountResultVO result = promotionEngineService.calculateDiscount(calculateDTO);

        if (Boolean.TRUE.equals(result.getSuccess())) {
            cart.setDiscounts(result.getDiscounts());
            cart.setDiscountDetails(result.getDiscountDetails());
            cart.setGifts(result.getGifts());
            cart.setDiscountAmount(result.getDiscountAmount());
            cart.setPayAmount(result.getPayAmount());
            cart.setDiscountCalculated(true);
            cart.setDiscountCalculateTime(System.currentTimeMillis());
        } else {
            cart.setDiscountCalculated(false);
        }

        cart.recalculate();
        return cart;
    }

    public Cart recalculateDiscount() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        cart = recalculateDiscount(cart);
        cartRedisStorage.saveCartMeta(tenantId, bizType, userId, cart);
        asyncSyncDb(tenantId, bizType, userId);
        return cart;
    }

    private void asyncSyncDb(String tenantId, String bizType, String userId) {
        if (cartHubProperties.getSync().getEnableDbSync()) {
            cartDbSyncService.markNeedSync(tenantId, bizType, userId);
        }
    }
}
