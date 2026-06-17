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
import com.carhub.domain.vo.TieredDiscountProgressVO;
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
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal result = BigDecimal.ZERO;

        switch (promotionType) {
            case "fixed":
            case "full_reduction":
                result = discountAmount != null ? discountAmount : BigDecimal.ZERO;
                if (result.compareTo(totalAmount) > 0) {
                    result = totalAmount;
                }
                break;
            case "percent":
            case "discount":
                if (discountPercent != null && discountPercent > 0 && discountPercent <= 100) {
                    BigDecimal discountRate = BigDecimal.valueOf(discountPercent)
                            .divide(BigDecimal.valueOf(100), 4, BigDecimal.ROUND_HALF_UP);
                    BigDecimal payRate = BigDecimal.ONE.subtract(discountRate);
                    result = totalAmount.multiply(payRate);
                }
                break;
            case "gift":
                result = BigDecimal.ZERO;
                break;
            default:
                result = discountAmount != null ? discountAmount : BigDecimal.ZERO;
                if (result.compareTo(totalAmount) > 0) {
                    result = totalAmount;
                }
                break;
        }

        if (result.compareTo(BigDecimal.ZERO) < 0) {
            result = BigDecimal.ZERO;
        }

        if (maxDiscountAmount != null && maxDiscountAmount.compareTo(BigDecimal.ZERO) > 0
                && result.compareTo(maxDiscountAmount) > 0) {
            result = maxDiscountAmount;
        }

        if (result.compareTo(totalAmount) > 0) {
            result = totalAmount;
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

    public TieredDiscountProgressVO calculateTieredDiscountProgress(String tenantId, String bizType,
                                                                     BigDecimal totalAmount) {
        List<PromotionActivityEntity> promotions = queryActiveFullReductionPromotions(tenantId, bizType);

        String remoteUrl = cartHubProperties.getPromotion().getPromotionListUrl();
        if (StringUtils.isNotBlank(remoteUrl)) {
            try {
                String url = remoteUrl + "?tenantId=" + tenantId + "&bizType=" + bizType;
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    List<PromotionVO> remotePromotions = JsonUtil.fromJsonList(response.getBody(), PromotionVO.class);
                    if (remotePromotions != null && !remotePromotions.isEmpty()) {
                        return buildTieredProgressFromVO(remotePromotions, totalAmount);
                    }
                }
            } catch (Exception e) {
                log.warn("Call remote promotion list for tiered discount failed: {}", e.getMessage());
            }
        }

        if (promotions.isEmpty()) {
            return buildEmptyProgress(totalAmount);
        }

        promotions.sort(Comparator.comparing(PromotionActivityEntity::getThresholdAmount));

        List<TieredDiscountProgressVO.TierInfo> allTiers = new ArrayList<>();
        TieredDiscountProgressVO.TierInfo currentTier = null;
        TieredDiscountProgressVO.TierInfo nextTier = null;
        BigDecimal currentDiscountAmount = BigDecimal.ZERO;

        for (PromotionActivityEntity p : promotions) {
            boolean reached = totalAmount.compareTo(p.getThresholdAmount()) >= 0;
            BigDecimal gapAmount = reached ? BigDecimal.ZERO :
                    p.getThresholdAmount().subtract(totalAmount);
            BigDecimal progressPercent = reached ? BigDecimal.valueOf(100) :
                    totalAmount.compareTo(BigDecimal.ZERO) > 0 ?
                            totalAmount.multiply(BigDecimal.valueOf(100))
                                    .divide(p.getThresholdAmount(), 0, BigDecimal.ROUND_HALF_UP) :
                            BigDecimal.ZERO;

            TieredDiscountProgressVO.TierInfo tier = TieredDiscountProgressVO.TierInfo.builder()
                    .promotionId(p.getPromotionId())
                    .promotionName(p.getPromotionName())
                    .thresholdAmount(p.getThresholdAmount())
                    .discountAmount(p.getDiscountAmount())
                    .reached(reached)
                    .gapAmount(gapAmount)
                    .progressPercent(progressPercent)
                    .build();

            allTiers.add(tier);

            if (reached) {
                currentTier = tier;
                currentDiscountAmount = p.getDiscountAmount();
            } else if (nextTier == null) {
                nextTier = tier;
            }
        }

        String progressTip = buildProgressTip(currentTier, nextTier, totalAmount);

        return TieredDiscountProgressVO.builder()
                .totalAmount(totalAmount)
                .currentDiscountAmount(currentDiscountAmount)
                .currentTier(currentTier)
                .nextTier(nextTier)
                .allTiers(allTiers)
                .progressTip(progressTip)
                .build();
    }

    private List<PromotionActivityEntity> queryActiveFullReductionPromotions(String tenantId, String bizType) {
        LambdaQueryWrapper<PromotionActivityEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromotionActivityEntity::getTenantId, tenantId)
                .eq(PromotionActivityEntity::getBizType, bizType)
                .eq(PromotionActivityEntity::getPromotionType, "full_reduction")
                .eq(PromotionActivityEntity::getStatus, 1)
                .eq(PromotionActivityEntity::getDeleted, 0)
                .le(PromotionActivityEntity::getStartTime, LocalDateTime.now())
                .ge(PromotionActivityEntity::getEndTime, LocalDateTime.now())
                .orderByAsc(PromotionActivityEntity::getThresholdAmount);
        return promotionActivityMapper.selectList(wrapper);
    }

    private TieredDiscountProgressVO buildTieredProgressFromVO(List<PromotionVO> promotions,
                                                                BigDecimal totalAmount) {
        List<PromotionVO> fullReduction = promotions.stream()
                .filter(p -> "full_reduction".equals(p.getPromotionType()) && Boolean.TRUE.equals(p.getAvailable()))
                .sorted(Comparator.comparing(PromotionVO::getThresholdAmount))
                .collect(Collectors.toList());

        if (fullReduction.isEmpty()) {
            return buildEmptyProgress(totalAmount);
        }

        List<TieredDiscountProgressVO.TierInfo> allTiers = new ArrayList<>();
        TieredDiscountProgressVO.TierInfo currentTier = null;
        TieredDiscountProgressVO.TierInfo nextTier = null;
        BigDecimal currentDiscountAmount = BigDecimal.ZERO;

        for (PromotionVO p : fullReduction) {
            boolean reached = totalAmount.compareTo(p.getThresholdAmount()) >= 0;
            BigDecimal gapAmount = reached ? BigDecimal.ZERO :
                    p.getThresholdAmount().subtract(totalAmount);
            BigDecimal progressPercent = reached ? BigDecimal.valueOf(100) :
                    totalAmount.compareTo(BigDecimal.ZERO) > 0 ?
                            totalAmount.multiply(BigDecimal.valueOf(100))
                                    .divide(p.getThresholdAmount(), 0, BigDecimal.ROUND_HALF_UP) :
                            BigDecimal.ZERO;

            TieredDiscountProgressVO.TierInfo tier = TieredDiscountProgressVO.TierInfo.builder()
                    .promotionId(p.getPromotionId())
                    .promotionName(p.getPromotionName())
                    .thresholdAmount(p.getThresholdAmount())
                    .discountAmount(p.getDiscountAmount())
                    .reached(reached)
                    .gapAmount(gapAmount)
                    .progressPercent(progressPercent)
                    .build();

            allTiers.add(tier);

            if (reached) {
                currentTier = tier;
                currentDiscountAmount = p.getDiscountAmount();
            } else if (nextTier == null) {
                nextTier = tier;
            }
        }

        String progressTip = buildProgressTip(currentTier, nextTier, totalAmount);

        return TieredDiscountProgressVO.builder()
                .totalAmount(totalAmount)
                .currentDiscountAmount(currentDiscountAmount)
                .currentTier(currentTier)
                .nextTier(nextTier)
                .allTiers(allTiers)
                .progressTip(progressTip)
                .build();
    }

    private TieredDiscountProgressVO buildEmptyProgress(BigDecimal totalAmount) {
        return TieredDiscountProgressVO.builder()
                .totalAmount(totalAmount)
                .currentDiscountAmount(BigDecimal.ZERO)
                .currentTier(null)
                .nextTier(null)
                .allTiers(Collections.emptyList())
                .progressTip("暂无满减活动")
                .build();
    }

    private String buildProgressTip(TieredDiscountProgressVO.TierInfo currentTier,
                                     TieredDiscountProgressVO.TierInfo nextTier,
                                     BigDecimal totalAmount) {
        if (currentTier != null && nextTier == null) {
            return "已享最高满减：满" + currentTier.getThresholdAmount().stripTrailingZeros().toPlainString()
                    + "减" + currentTier.getDiscountAmount().stripTrailingZeros().toPlainString();
        }
        if (currentTier != null && nextTier != null) {
            return "已享满" + currentTier.getThresholdAmount().stripTrailingZeros().toPlainString()
                    + "减" + currentTier.getDiscountAmount().stripTrailingZeros().toPlainString()
                    + "，再买" + nextTier.getGapAmount().stripTrailingZeros().toPlainString()
                    + "元可享满" + nextTier.getThresholdAmount().stripTrailingZeros().toPlainString()
                    + "减" + nextTier.getDiscountAmount().stripTrailingZeros().toPlainString();
        }
        if (nextTier != null) {
            return "再买" + nextTier.getGapAmount().stripTrailingZeros().toPlainString()
                    + "元可享满" + nextTier.getThresholdAmount().stripTrailingZeros().toPlainString()
                    + "减" + nextTier.getDiscountAmount().stripTrailingZeros().toPlainString();
        }
        return "暂无满减活动";
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
