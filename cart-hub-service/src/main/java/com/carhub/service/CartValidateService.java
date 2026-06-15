package com.carhub.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.carhub.common.constant.CartConstant;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.ProductValidateDTO;
import com.carhub.domain.entity.BizConfigEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartValidateService {

    private final CartHubProperties cartHubProperties;
    private final BizConfigService bizConfigService;
    private final CartRedisStorage cartRedisStorage;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询校验并重算价格
     * 价格变动后会回写Redis，并标记price_changed
     */
    public void validateAndRecalculate(String tenantId, String bizType, String userId, Cart cart) {
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return;
        }
        if (!isValidateEnabled(tenantId, bizType)) {
            return;
        }

        List<ProductValidateDTO> validateList = buildValidateRequest(cart);
        List<CartItem> items = cart.getItems();

        List<ProductValidateDTO> needRemoteValidate = new ArrayList<>();
        Map<String, ProductValidateResult> cacheMap = new HashMap<>();

        for (ProductValidateDTO dto : validateList) {
            ProductValidateResult cached = getCachedResult(bizType, dto.getSkuId());
            if (cached != null) {
                cacheMap.put(dto.getSkuId(), cached);
            } else {
                needRemoteValidate.add(dto);
            }
        }

        if (!needRemoteValidate.isEmpty()) {
            List<ProductValidateResult> remoteResults = remoteValidate(tenantId, bizType, needRemoteValidate);
            for (ProductValidateResult r : remoteResults) {
                cacheMap.put(r.getSkuId(), r);
                cacheResult(bizType, r);
            }
        }

        boolean hasChanged = applyValidateResult(items, cacheMap);
        cart.recalculate();

        if (hasChanged) {
            persistUpdatedItems(tenantId, bizType, userId, items, cacheMap);
        }
    }

    /**
     * 单个SKU重校验（Kafka消息触发用）
     */
    public void revalidateSingleSku(String tenantId, String bizType, String userId, Cart cart, String skuId) {
        if (cart == null || cart.getItems() == null) {
            return;
        }
        CartItem targetItem = cart.getItemBySku(skuId);
        if (targetItem == null) {
            return;
        }

        ProductValidateDTO dto = new ProductValidateDTO();
        dto.setSkuId(targetItem.getSkuId());
        dto.setSpuId(targetItem.getSpuId());
        dto.setUnitPrice(targetItem.getUnitPrice());
        dto.setQuantity(targetItem.getQuantity());

        List<ProductValidateResult> results = remoteValidate(tenantId, bizType, Collections.singletonList(dto));
        if (!results.isEmpty()) {
            ProductValidateResult r = results.get(0);
            boolean changed = applySingleResult(targetItem, r);
            cacheResult(bizType, r);
            cart.recalculate();
            if (changed) {
                cartRedisStorage.updateItem(tenantId, bizType, userId, targetItem);
            }
        }
    }

    private List<ProductValidateDTO> buildValidateRequest(Cart cart) {
        return cart.getItems().stream()
                .map(item -> {
                    ProductValidateDTO dto = new ProductValidateDTO();
                    dto.setSkuId(item.getSkuId());
                    dto.setSpuId(item.getSpuId());
                    dto.setUnitPrice(item.getUnitPrice());
                    dto.setQuantity(item.getQuantity());
                    return dto;
                }).collect(Collectors.toList());
    }

    /**
     * 从业务配置表获取校验地址，而非全局配置
     */
    private List<ProductValidateResult> remoteValidate(String tenantId, String bizType,
                                                        List<ProductValidateDTO> list) {
        BizConfigEntity config = bizConfigService.getConfig(tenantId, bizType);
        String validateUrl = (config != null && StringUtils.isNotBlank(config.getValidateUrl()))
                ? config.getValidateUrl()
                : cartHubProperties.getValidate().getValidateUrl();

        if (StringUtils.isBlank(validateUrl)) {
            log.warn("validateUrl not configured for bizType={}, skip remote validation", bizType);
            return new ArrayList<>();
        }

        int timeoutMs = (config != null && config.getValidateTimeout() != null)
                ? config.getValidateTimeout()
                : cartHubProperties.getValidate().getTimeoutMs();

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenantId);
            body.put("bizType", bizType);
            body.put("products", list);

            HttpRequest request = HttpUtil.createPost(validateUrl)
                    .header("Content-Type", "application/json")
                    .header(CartConstant.HEADER_TENANT_ID, tenantId)
                    .header(CartConstant.HEADER_BIZ_TYPE, bizType)
                    .timeout(timeoutMs)
                    .body(JSON.toJSONString(body));

            HttpResponse response = request.execute();
            if (!response.isOk()) {
                log.warn("validate request failed, bizType={}, status={}", bizType, response.getStatus());
                return new ArrayList<>();
            }
            JSONObject json = JSON.parseObject(response.body());
            if (json == null || json.getInteger("code") != null && json.getInteger("code") != 200) {
                log.warn("validate response error, bizType={}, body={}", bizType, response.body());
                return new ArrayList<>();
            }
            String dataStr = json.getString("data");
            if (StringUtils.isBlank(dataStr)) {
                return new ArrayList<>();
            }
            return JSON.parseArray(dataStr, ProductValidateResult.class);
        } catch (Exception e) {
            log.error("remote validate exception, bizType={}", bizType, e);
            return new ArrayList<>();
        }
    }

    /**
     * 应用校验结果到商品列表
     * @return 是否有商品发生了变化（需要回写）
     */
    private boolean applyValidateResult(List<CartItem> items, Map<String, ProductValidateResult> resultMap) {
        boolean hasChanged = false;
        for (CartItem item : items) {
            ProductValidateResult r = resultMap.get(item.getSkuId());
            if (r != null) {
                if (applySingleResult(item, r)) {
                    hasChanged = true;
                }
            }
        }
        return hasChanged;
    }

    /**
     * 应用单个校验结果
     * @return 商品信息是否变化
     */
    private boolean applySingleResult(CartItem item, ProductValidateResult r) {
        boolean changed = false;

        if (r.getStock() != null && !r.getStock().equals(item.getStock())) {
            item.setStock(r.getStock());
            changed = true;
        }

        if (r.getOnShelf() != null && !r.getOnShelf().equals(item.getOnShelf())) {
            item.setOnShelf(r.getOnShelf());
            changed = true;
        }

        if (!Boolean.TRUE.equals(r.getValid())) {
            String errorMsg = StringUtils.defaultIfBlank(r.getErrorMessage(), "商品不可购买");
            if (!errorMsg.equals(item.getInvalidMessage())) {
                item.setInvalidMessage(errorMsg);
                changed = true;
            }
        } else {
            if (item.getInvalidMessage() != null && !item.getInvalidMessage().isEmpty()) {
                item.setInvalidMessage(null);
                changed = true;
            }
        }

        if (r.getCurrentPrice() != null && item.getUnitPrice() != null
                && r.getCurrentPrice().compareTo(item.getUnitPrice()) != 0) {
            item.setOldPrice(item.getUnitPrice());
            item.setUnitPrice(r.getCurrentPrice());
            item.setPriceChanged(true);
            changed = true;
        }

        if (StringUtils.isNotBlank(r.getItemName()) && !r.getItemName().equals(item.getItemName())) {
            item.setItemName(r.getItemName());
            changed = true;
        }

        if (StringUtils.isNotBlank(r.getItemImage()) && !r.getItemImage().equals(item.getItemImage())) {
            item.setItemImage(r.getItemImage());
            changed = true;
        }

        if (Boolean.TRUE.equals(r.getValid())) {
            item.recalculate();
        }

        return changed;
    }

    /**
     * 把校验后有变动的商品回写到Redis，保留价格变动标记
     */
    private void persistUpdatedItems(String tenantId, String bizType, String userId,
                                      List<CartItem> items, Map<String, ProductValidateResult> resultMap) {
        for (CartItem item : items) {
            ProductValidateResult r = resultMap.get(item.getSkuId());
            if (r != null) {
                try {
                    cartRedisStorage.updateItem(tenantId, bizType, userId, item);
                } catch (Exception e) {
                    log.error("persist updated item error: tenantId={}, bizType={}, userId={}, skuId={}",
                            tenantId, bizType, userId, item.getSkuId(), e);
                }
            }
        }
    }

    private boolean isValidateEnabled(String tenantId, String bizType) {
        if (!cartHubProperties.getValidate().getEnable()) {
            return false;
        }
        BizConfigEntity config = bizConfigService.getConfig(tenantId, bizType);
        return config != null && StringUtils.isNotBlank(config.getValidateUrl());
    }

    private ProductValidateResult getCachedResult(String bizType, String skuId) {
        String key = RedisKeyConstant.buildValidateCacheKey(bizType, skuId);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JsonUtil.fromJson(json, ProductValidateResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void cacheResult(String bizType, ProductValidateResult result) {
        String key = RedisKeyConstant.buildValidateCacheKey(bizType, result.getSkuId());
        BizConfigEntity config = null;
        try {
            config = bizConfigService.getConfig(
                    com.carhub.common.context.CartContextHolder.getTenantId(), bizType);
        } catch (Exception ignored) {
        }
        int expire = (config != null && config.getValidateCacheSec() != null)
                ? config.getValidateCacheSec()
                : cartHubProperties.getValidate().getCacheSeconds();
        stringRedisTemplate.opsForValue().set(key, JsonUtil.toJson(result), Duration.ofSeconds(expire));
    }

    @Async
    public void asyncRevalidateUsers(String tenantId, String bizType, Set<String> userIds) {
        log.info("asyncRevalidateUsers start: tenantId={}, bizType={}, userCount={}", tenantId, bizType, userIds.size());
        int processed = 0;
        for (String userId : userIds) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
                Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
                if (cart != null && cart.getItems() != null && !cart.getItems().isEmpty()) {
                    validateAndRecalculate(tenantId, bizType, userId, cart);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("asyncRevalidateUsers error, userId={}", userId, e);
            }
            processed++;
            if (log.isDebugEnabled() && processed % 100 == 0) {
                log.debug("asyncRevalidateUsers progress: {}/{}", processed, userIds.size());
            }
        }
        log.info("asyncRevalidateUsers done: {}/{}", processed, userIds.size());
    }

    @lombok.Data
    public static class ProductValidateResult {
        private String skuId;
        private String spuId;
        private Boolean valid;
        private Boolean onShelf;
        private Integer stock;
        private BigDecimal currentPrice;
        private BigDecimal originalPrice;
        private String itemName;
        private String itemImage;
        private String errorMessage;
    }

}
