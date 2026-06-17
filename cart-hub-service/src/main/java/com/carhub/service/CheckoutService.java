package com.carhub.service;

import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.CheckoutConfirmDTO;
import com.carhub.domain.dto.CheckoutCreateDTO;
import com.carhub.domain.entity.CheckoutSnapshotEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.CheckoutSnapshotVO;
import com.carhub.mapper.CheckoutSnapshotMapper;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final CartRedisStorage cartRedisStorage;
    private final CartValidateService cartValidateService;
    private final CheckoutSnapshotMapper checkoutSnapshotMapper;
    private final CartHubProperties cartHubProperties;
    private final RedissonClient redissonClient;
    private final RestTemplate restTemplate;
    private final InventoryService inventoryService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    public void init() {
        log.info("CheckoutService initialized, expireMinutes={}, enableStockLock={}",
                cartHubProperties.getCheckout().getExpireMinutes(),
                cartHubProperties.getCheckout().getEnableStockLock());
    }

    @Transactional(rollbackFor = Exception.class)
    public CheckoutSnapshotVO createCheckout(CheckoutCreateDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        checkUserConcurrentLimit(tenantId, bizType, userId);

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.CHECKOUT_CART_EMPTY);
        }

        List<CartItem> selectedItems = cart.getItems().stream()
                .filter(i -> Boolean.TRUE.equals(i.getSelected()))
                .collect(Collectors.toList());

        if (dto.getSkuIds() != null && !dto.getSkuIds().isEmpty()) {
            Set<String> skuIdSet = new HashSet<>(dto.getSkuIds());
            selectedItems = selectedItems.stream()
                    .filter(i -> skuIdSet.contains(i.getSkuId()))
                    .collect(Collectors.toList());
        }

        if (selectedItems.isEmpty()) {
            throw new BusinessException(ResultCode.CHECKOUT_CART_EMPTY);
        }

        boolean hasInvalidItem = selectedItems.stream()
                .anyMatch(i -> Boolean.FALSE.equals(i.getOnShelf())
                        || (i.getInvalidMessage() != null && !i.getInvalidMessage().isEmpty()));
        if (hasInvalidItem) {
            throw new BusinessException(ResultCode.CHECKOUT_HAS_INVALID_ITEM);
        }

        boolean hasPriceChanged = selectedItems.stream()
                .anyMatch(i -> Boolean.TRUE.equals(i.getPriceChanged()));
        if (hasPriceChanged) {
            throw new BusinessException(ResultCode.CHECKOUT_PRICE_CHANGED);
        }

        List<com.carhub.domain.vo.InventoryCheckVO.InventoryItemVO> stockShortageItems = null;
        boolean hasStockShortage = false;

        if (Boolean.TRUE.equals(cartHubProperties.getCheckout().getEnableStockCheck())) {
            List<com.carhub.domain.dto.InventoryCheckDTO.InventoryItemDTO> checkItems = selectedItems.stream()
                    .map(item -> com.carhub.domain.dto.InventoryCheckDTO.InventoryItemDTO.builder()
                            .skuId(item.getSkuId())
                            .spuId(item.getSpuId())
                            .quantity(item.getQuantity())
                            .itemName(item.getItemName())
                            .build())
                    .collect(Collectors.toList());

            com.carhub.domain.vo.InventoryCheckVO inventoryCheck = inventoryService.checkInventory(checkItems);
            if (inventoryCheck != null && inventoryCheck.isHasShortage()) {
                hasStockShortage = true;
                stockShortageItems = inventoryCheck.getShortageItems();
                log.warn("Stock shortage detected for user {}, {} items: {}",
                        userId, stockShortageItems.size(),
                        stockShortageItems.stream()
                                .map(item -> item.getSkuId() + "(" + item.getShortageReason() + ")")
                                .collect(Collectors.joining(", ")));
                throw new BusinessException(ResultCode.CHECKOUT_STOCK_NOT_ENOUGH.getCode(),
                        buildStockShortageMessage(stockShortageItems));
            }
        }

        Cart checkoutCart = buildCheckoutCart(cart, selectedItems);

        String checkoutToken = generateCheckoutToken();
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(
                cartHubProperties.getCheckout().getExpireMinutes());

        Integer stockStatus = 0;
        String stockLockCode = null;

        if (Boolean.TRUE.equals(cartHubProperties.getCheckout().getEnableStockLock())) {
            try {
                stockLockCode = lockStock(tenantId, bizType, userId, selectedItems, expireTime);
                stockStatus = 1;
            } catch (Exception e) {
                log.error("Stock lock failed for user: {}", userId, e);
                stockStatus = 2;
                throw new BusinessException(ResultCode.CHECKOUT_STOCK_LOCK_FAILED.getCode(),
                        "库存预占失败：" + e.getMessage());
            }
        }

        CheckoutSnapshotEntity entity = new CheckoutSnapshotEntity();
        entity.setCheckoutToken(checkoutToken);
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setUserId(userId);
        entity.setCartSnapshot(JsonUtil.toJson(checkoutCart));
        entity.setItemCount(checkoutCart.getItemCount());
        entity.setTotalQuantity(checkoutCart.getTotalQuantity());
        entity.setTotalAmount(checkoutCart.getTotalAmount());
        entity.setDiscountAmount(checkoutCart.getDiscountAmount());
        entity.setPayAmount(checkoutCart.getPayAmount());
        entity.setStockStatus(stockStatus);
        entity.setStockLockCode(stockLockCode);
        entity.setStatus(0);
        entity.setExpireTime(expireTime);
        entity.setSource(StringUtils.defaultIfBlank(dto.getSource(), CartContextHolder.getSource()));
        entity.setClientIp(CartContextHolder.getClientIp());

        Map<String, Object> extInfo = new HashMap<>();
        if (StringUtils.isNotBlank(dto.getAddressId())) {
            extInfo.put("addressId", dto.getAddressId());
        }
        if (StringUtils.isNotBlank(dto.getRemark())) {
            extInfo.put("remark", dto.getRemark());
        }
        if (!extInfo.isEmpty()) {
            entity.setExtInfo(JsonUtil.toJson(extInfo));
        }

        checkoutSnapshotMapper.insert(entity);

        cacheCheckoutSnapshot(checkoutToken, entity);

        addUserCheckoutToken(tenantId, bizType, userId, checkoutToken, expireTime);

        log.info("Checkout snapshot created: token={}, userId={}, itemCount={}, amount={}, hasStockShortage={}",
                checkoutToken, userId, entity.getItemCount(), entity.getPayAmount(), hasStockShortage);

        return convertToVO(entity, checkoutCart, hasStockShortage, stockShortageItems);
    }

    public CheckoutSnapshotVO getCheckout(String checkoutToken) {
        if (StringUtils.isBlank(checkoutToken)) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        CheckoutSnapshotEntity entity = getSnapshotFromCache(checkoutToken);
        if (entity == null) {
            entity = checkoutSnapshotMapper.findByToken(checkoutToken);
            if (entity != null) {
                cacheCheckoutSnapshot(checkoutToken, entity);
            }
        }

        if (entity == null || entity.getDeleted() != null && entity.getDeleted() == 1) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        if (entity.getStatus() == 3) {
            throw new BusinessException(ResultCode.CHECKOUT_EXPIRED);
        }

        if (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now())) {
            if (entity.getStatus() == 0) {
                expireCheckout(entity);
            }
            throw new BusinessException(ResultCode.CHECKOUT_EXPIRED);
        }

        String userId = CartContextHolder.getUserId();
        if (StringUtils.isNotBlank(userId) && !userId.equals(entity.getUserId())) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        Cart cart = parseCartSnapshot(entity.getCartSnapshot());
        return convertToVO(entity, cart);
    }

    @Transactional(rollbackFor = Exception.class)
    public CheckoutSnapshotVO confirmCheckout(CheckoutConfirmDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        CheckoutSnapshotEntity entity = checkoutSnapshotMapper.findByToken(dto.getCheckoutToken());
        if (entity == null) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        if (entity.getStatus() == 1) {
            throw new BusinessException(ResultCode.CHECKOUT_ALREADY_CONFIRMED);
        }

        if (entity.getStatus() == 2) {
            throw new BusinessException(ResultCode.CHECKOUT_ALREADY_CANCELED);
        }

        if (entity.getStatus() == 3 ||
                (entity.getExpireTime() != null && entity.getExpireTime().isBefore(LocalDateTime.now()))) {
            throw new BusinessException(ResultCode.CHECKOUT_EXPIRED);
        }

        entity.setStatus(1);
        if (StringUtils.isNotBlank(dto.getOrderNo())) {
            entity.setOrderNo(dto.getOrderNo());
        }
        checkoutSnapshotMapper.updateById(entity);

        evictCheckoutCache(dto.getCheckoutToken());

        removeSelectedItemsFromCart(tenantId, bizType, userId, entity);

        removeUserCheckoutToken(tenantId, bizType, userId, dto.getCheckoutToken());

        Cart cart = parseCartSnapshot(entity.getCartSnapshot());
        log.info("Checkout confirmed: token={}, orderNo={}, userId={}",
                dto.getCheckoutToken(), dto.getOrderNo(), userId);

        return convertToVO(entity, cart);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancelCheckout(String checkoutToken) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        CheckoutSnapshotEntity entity = checkoutSnapshotMapper.findByToken(checkoutToken);
        if (entity == null) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        if (entity.getStatus() == 2) {
            return true;
        }

        if (entity.getStatus() == 1) {
            throw new BusinessException("结算已确认，无法取消");
        }

        entity.setStatus(2);
        checkoutSnapshotMapper.updateById(entity);

        if (entity.getStockStatus() != null && entity.getStockStatus() == 1
                && StringUtils.isNotBlank(entity.getStockLockCode())) {
            try {
                releaseStock(entity.getStockLockCode());
                entity.setStockStatus(3);
                checkoutSnapshotMapper.updateById(entity);
            } catch (Exception e) {
                log.warn("Release stock failed for token: {}", checkoutToken, e);
            }
        }

        evictCheckoutCache(checkoutToken);
        removeUserCheckoutToken(tenantId, bizType, userId, checkoutToken);

        log.info("Checkout canceled: token={}, userId={}", checkoutToken, userId);

        return true;
    }

    public CheckoutSnapshotVO refreshCheckout(String checkoutToken) {
        String userId = CartContextHolder.getUserId();
        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户未登录，请先登录");
        }

        CheckoutSnapshotEntity entity = checkoutSnapshotMapper.findByToken(checkoutToken);
        if (entity == null) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(ResultCode.CHECKOUT_TOKEN_INVALID);
        }

        if (entity.getStatus() != 0) {
            throw new BusinessException("仅待确认状态的结算可续期");
        }

        LocalDateTime newExpireTime = LocalDateTime.now().plusMinutes(
                cartHubProperties.getCheckout().getExpireMinutes());
        entity.setExpireTime(newExpireTime);
        checkoutSnapshotMapper.updateById(entity);

        cacheCheckoutSnapshot(checkoutToken, entity);
        updateUserCheckoutTokenExpire(entity.getTenantId(), entity.getBizType(),
                entity.getUserId(), checkoutToken, newExpireTime);

        Cart cart = parseCartSnapshot(entity.getCartSnapshot());
        log.info("Checkout refreshed: token={}, newExpireTime={}", checkoutToken, newExpireTime);

        return convertToVO(entity, cart);
    }

    public List<CheckoutSnapshotVO> listMyCheckouts(Integer status) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            return Collections.emptyList();
        }

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CheckoutSnapshotEntity> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("tenant_id", tenantId)
                .eq("biz_type", bizType)
                .eq("user_id", userId)
                .eq("deleted", 0);
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");

        List<CheckoutSnapshotEntity> entities = checkoutSnapshotMapper.selectList(wrapper);

        return entities.stream()
                .map(e -> {
                    Cart cart = parseCartSnapshot(e.getCartSnapshot());
                    return convertToVO(e, cart);
                })
                .collect(Collectors.toList());
    }

    @Scheduled(fixedDelay = 60000)
    public void expireCheckoutSnapshots() {
        try {
            processExpiredSnapshots();
        } catch (Exception e) {
            log.error("Expire checkout snapshots task failed", e);
        }
    }

    private void processExpiredSnapshots() {
        LocalDateTime now = LocalDateTime.now();
        int processedCount = 0;

        while (true) {
            List<String> expiredTokens = checkoutSnapshotMapper.findExpiredTokens(now, 100);
            if (expiredTokens == null || expiredTokens.isEmpty()) {
                break;
            }

            for (String token : expiredTokens) {
                try {
                    processSingleExpiredSnapshot(token);
                    processedCount++;
                } catch (Exception e) {
                    log.warn("Process expired snapshot failed, token={}", token, e);
                }
            }

            if (expiredTokens.size() < 100) {
                break;
            }
        }

        if (processedCount > 0) {
            log.info("Expired {} checkout snapshots", processedCount);
        }
    }

    private void processSingleExpiredSnapshot(String token) {
        CheckoutSnapshotEntity entity = checkoutSnapshotMapper.findByToken(token);
        if (entity == null || entity.getStatus() != 0) {
            return;
        }

        if (entity.getExpireTime() == null || entity.getExpireTime().isAfter(LocalDateTime.now())) {
            return;
        }

        if (entity.getStockStatus() != null && entity.getStockStatus() == 1
                && StringUtils.isNotBlank(entity.getStockLockCode())) {
            try {
                releaseStock(entity.getStockLockCode());
                entity.setStockStatus(3);
                log.info("Released stock for expired checkout: {}, lockCode={}", token, entity.getStockLockCode());
            } catch (Exception e) {
                log.warn("Release stock failed for expired checkout: {}", token, e);
            }
        }

        entity.setStatus(3);
        checkoutSnapshotMapper.updateById(entity);

        try {
            notifyUserExpired(entity);
        } catch (Exception e) {
            log.warn("Notify user expired failed for checkout: {}", token, e);
        }

        evictCheckoutCache(token);
        removeUserCheckoutToken(entity.getTenantId(), entity.getBizType(), entity.getUserId(), token);
    }

    private String lockStock(String tenantId, String bizType, String userId,
                             List<CartItem> items, LocalDateTime expireTime) {
        if (Boolean.TRUE.equals(cartHubProperties.getCheckout().getMockStock())
                && StringUtils.isBlank(cartHubProperties.getCheckout().getStockLockUrl())) {
            return mockLockStock(tenantId, bizType, userId, items, expireTime);
        }

        if (StringUtils.isBlank(cartHubProperties.getCheckout().getStockLockUrl())) {
            log.warn("Stock lock URL not configured and mock stock disabled, skip stock lock");
            return null;
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("tenantId", tenantId);
            request.put("bizType", bizType);
            request.put("userId", userId);
            request.put("expireTime", expireTime.toString());

            List<Map<String, Object>> skuList = items.stream()
                    .map(item -> {
                        Map<String, Object> skuMap = new HashMap<>();
                        skuMap.put("skuId", item.getSkuId());
                        skuMap.put("quantity", item.getQuantity());
                        return skuMap;
                    })
                    .collect(Collectors.toList());
            request.put("items", skuList);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            org.springframework.http.ResponseEntity<Map> response = restTemplate.postForEntity(
                    cartHubProperties.getCheckout().getStockLockUrl(),
                    entity,
                    Map.class);

            Map<String, Object> body = response.getBody();
            if (body != null && Boolean.TRUE.equals(body.get("success"))) {
                String lockCode = (String) body.get("lockCode");
                log.info("Stock locked successfully, lockCode={}, userId={}", lockCode, userId);
                return lockCode;
            } else {
                String message = body != null ? (String) body.get("message") : "库存预占失败";
                throw new BusinessException(ResultCode.CHECKOUT_STOCK_LOCK_FAILED.getCode(), message);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Call stock lock API failed", e);
            if (Boolean.TRUE.equals(cartHubProperties.getCheckout().getMockStock())) {
                log.warn("Fallback to mock stock lock due to API failure");
                return mockLockStock(tenantId, bizType, userId, items, expireTime);
            }
            throw new BusinessException(ResultCode.CHECKOUT_STOCK_LOCK_FAILED.getCode(),
                    "库存预占接口调用失败");
        }
    }

    private String mockLockStock(String tenantId, String bizType, String userId,
                                List<CartItem> items, LocalDateTime expireTime) {
        String lockCode = "MOCK_LOCK_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        log.info("Mock stock locked: lockCode={}, tenantId={}, bizType={}, userId={}, itemCount={}",
                lockCode, tenantId, bizType, userId, items.size());
        return lockCode;
    }

    private void releaseStock(String lockCode) {
        if (StringUtils.isBlank(lockCode)) {
            return;
        }

        if (StringUtils.isNotBlank(lockCode) && lockCode.startsWith("MOCK_LOCK_")) {
            mockReleaseStock(lockCode);
            return;
        }

        if (StringUtils.isBlank(cartHubProperties.getCheckout().getStockReleaseUrl())) {
            log.warn("Stock release URL not configured, skip release for lockCode={}", lockCode);
            return;
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("lockCode", lockCode);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            restTemplate.postForEntity(
                    cartHubProperties.getCheckout().getStockReleaseUrl(),
                    entity,
                    Map.class);
            log.info("Stock released successfully, lockCode={}", lockCode);
        } catch (Exception e) {
            log.warn("Call stock release API failed, lockCode={}", lockCode, e);
        }
    }

    private void mockReleaseStock(String lockCode) {
        log.info("Mock stock released: lockCode={}", lockCode);
    }

    private void expireCheckout(CheckoutSnapshotEntity entity) {
        entity.setStatus(3);
        checkoutSnapshotMapper.updateById(entity);
        evictCheckoutCache(entity.getCheckoutToken());

        if (entity.getStockStatus() != null && entity.getStockStatus() == 1
                && StringUtils.isNotBlank(entity.getStockLockCode())) {
            try {
                releaseStock(entity.getStockLockCode());
                entity.setStockStatus(3);
                checkoutSnapshotMapper.updateById(entity);
            } catch (Exception e) {
                log.warn("Release stock failed for expired checkout: {}", entity.getCheckoutToken(), e);
            }
        }

        notifyUserExpired(entity);
    }

    private void notifyUserExpired(CheckoutSnapshotEntity entity) {
        if (StringUtils.isBlank(cartHubProperties.getCheckout().getNotifyUrl())) {
            return;
        }
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("userId", entity.getUserId());
            request.put("checkoutToken", entity.getCheckoutToken());
            request.put("tenantId", entity.getTenantId());
            request.put("bizType", entity.getBizType());
            request.put("type", "checkout_expired");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> httpEntity = new HttpEntity<>(request, headers);

            restTemplate.postForEntity(
                    cartHubProperties.getCheckout().getNotifyUrl(),
                    httpEntity,
                    Map.class);
        } catch (Exception e) {
            log.warn("Notify user expired failed, userId={}", entity.getUserId(), e);
        }
    }

    private Cart buildCheckoutCart(Cart originalCart, List<CartItem> selectedItems) {
        Cart checkoutCart = new Cart();
        checkoutCart.setTenantId(originalCart.getTenantId());
        checkoutCart.setBizType(originalCart.getBizType());
        checkoutCart.setUserId(originalCart.getUserId());
        checkoutCart.setItems(new ArrayList<>(selectedItems));
        checkoutCart.setDiscounts(originalCart.getDiscounts());
        checkoutCart.setSelectedCouponId(originalCart.getSelectedCouponId());
        checkoutCart.setSelectedPromotionIds(originalCart.getSelectedPromotionIds());
        checkoutCart.setCouponCode(originalCart.getCouponCode());
        checkoutCart.setDiscountDetails(originalCart.getDiscountDetails());
        checkoutCart.setGifts(originalCart.getGifts());
        checkoutCart.setDiscountCalculated(originalCart.getDiscountCalculated());
        checkoutCart.setDiscountCalculateTime(originalCart.getDiscountCalculateTime());
        checkoutCart.recalculate();
        return checkoutCart;
    }

    private String generateCheckoutToken() {
        return "CK" + System.currentTimeMillis() + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16).toUpperCase();
    }

    private CheckoutSnapshotVO convertToVO(CheckoutSnapshotEntity entity, Cart cart) {
        return convertToVO(entity, cart, false, null);
    }

    private CheckoutSnapshotVO convertToVO(CheckoutSnapshotEntity entity, Cart cart,
                                           boolean hasStockShortage,
                                           List<com.carhub.domain.vo.InventoryCheckVO.InventoryItemVO> stockShortageItems) {
        CheckoutSnapshotVO vo = CheckoutSnapshotVO.builder()
                .checkoutToken(entity.getCheckoutToken())
                .tenantId(entity.getTenantId())
                .bizType(entity.getBizType())
                .userId(entity.getUserId())
                .cartSnapshot(cart)
                .itemCount(entity.getItemCount())
                .totalQuantity(entity.getTotalQuantity())
                .totalAmount(entity.getTotalAmount())
                .discountAmount(entity.getDiscountAmount())
                .payAmount(entity.getPayAmount())
                .stockStatus(entity.getStockStatus())
                .stockLockCode(entity.getStockLockCode())
                .status(entity.getStatus())
                .orderNo(entity.getOrderNo())
                .expireTime(entity.getExpireTime())
                .source(entity.getSource())
                .createTime(entity.getCreateTime())
                .hasStockShortage(hasStockShortage)
                .stockShortageItems(stockShortageItems)
                .build();

        if (entity.getExpireTime() != null) {
            long seconds = Duration.between(LocalDateTime.now(), entity.getExpireTime()).getSeconds();
            vo.setExpireSeconds(Math.max(0, seconds));
        }

        return vo;
    }

    private Cart parseCartSnapshot(String cartSnapshotJson) {
        if (StringUtils.isBlank(cartSnapshotJson)) {
            return null;
        }
        try {
            return JsonUtil.fromJson(cartSnapshotJson, Cart.class);
        } catch (Exception e) {
            log.error("Parse cart snapshot failed", e);
            return null;
        }
    }

    private void cacheCheckoutSnapshot(String token, CheckoutSnapshotEntity entity) {
        try {
            String key = RedisKeyConstant.buildCheckoutKey(token);
            long ttl = cartHubProperties.getCheckout().getExpireMinutes() * 60L;
            stringRedisTemplate.opsForValue().set(key, JsonUtil.toJson(entity),
                    Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("Cache checkout snapshot failed, token={}", token, e);
        }
    }

    private CheckoutSnapshotEntity getSnapshotFromCache(String token) {
        try {
            String key = RedisKeyConstant.buildCheckoutKey(token);
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StringUtils.isNotBlank(json)) {
                return JsonUtil.fromJson(json, CheckoutSnapshotEntity.class);
            }
        } catch (Exception e) {
            log.warn("Get checkout snapshot from cache failed, token={}", token, e);
        }
        return null;
    }

    private void evictCheckoutCache(String token) {
        try {
            String key = RedisKeyConstant.buildCheckoutKey(token);
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Evict checkout cache failed, token={}", token, e);
        }
    }

    private void checkUserConcurrentLimit(String tenantId, String bizType, String userId) {
        int maxConcurrent = cartHubProperties.getCheckout().getMaxConcurrentPerUser();
        if (maxConcurrent <= 0) {
            return;
        }

        String key = RedisKeyConstant.buildCheckoutUserKey(tenantId, bizType, userId);
        Set<String> tokens = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        if (tokens != null && tokens.size() >= maxConcurrent) {
            throw new BusinessException("您有太多待支付的订单，请先完成或取消后再结算");
        }
    }

    private void addUserCheckoutToken(String tenantId, String bizType, String userId,
                                      String token, LocalDateTime expireTime) {
        try {
            String key = RedisKeyConstant.buildCheckoutUserKey(tenantId, bizType, userId);
            double score = expireTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            stringRedisTemplate.opsForZSet().add(key, token, score);
        } catch (Exception e) {
            log.warn("Add user checkout token failed, userId={}", userId, e);
        }
    }

    private void removeUserCheckoutToken(String tenantId, String bizType, String userId, String token) {
        try {
            String key = RedisKeyConstant.buildCheckoutUserKey(tenantId, bizType, userId);
            stringRedisTemplate.opsForZSet().remove(key, token);
        } catch (Exception e) {
            log.warn("Remove user checkout token failed, userId={}", userId, e);
        }
    }

    private void updateUserCheckoutTokenExpire(String tenantId, String bizType, String userId,
                                               String token, LocalDateTime expireTime) {
        try {
            String key = RedisKeyConstant.buildCheckoutUserKey(tenantId, bizType, userId);
            double score = expireTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            stringRedisTemplate.opsForZSet().add(key, token, score);
        } catch (Exception e) {
            log.warn("Update user checkout token expire failed, userId={}", userId, e);
        }
    }

    private void removeSelectedItemsFromCart(String tenantId, String bizType, String userId,
                                             CheckoutSnapshotEntity entity) {
        try {
            Cart cart = parseCartSnapshot(entity.getCartSnapshot());
            if (cart != null && cart.getItems() != null) {
                for (CartItem item : cart.getItems()) {
                    cartRedisStorage.removeItem(tenantId, bizType, userId, item.getSkuId());
                }
            }
        } catch (Exception e) {
            log.warn("Remove selected items from cart failed, userId={}", userId, e);
        }
    }

    private String buildStockShortageMessage(List<com.carhub.domain.vo.InventoryCheckVO.InventoryItemVO> shortageItems) {
        if (shortageItems == null || shortageItems.isEmpty()) {
            return "商品库存不足";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下商品库存不足：");
        for (int i = 0; i < Math.min(shortageItems.size(), 3); i++) {
            com.carhub.domain.vo.InventoryCheckVO.InventoryItemVO item = shortageItems.get(i);
            sb.append(i + 1).append(". ")
                    .append(StringUtils.defaultIfBlank(item.getItemName(), "商品"))
                    .append("（")
                    .append(StringUtils.defaultIfBlank(item.getShortageReason(), "库存不足"))
                    .append("）；");
        }
        if (shortageItems.size() > 3) {
            sb.append("等 ").append(shortageItems.size()).append(" 个商品");
        }
        return sb.toString();
    }

}
