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
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "redisTemplate")
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    public void validateAndRecalculate(String tenantId, String bizType, String userId, Cart cart) {
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return;
        }
        if (!cartHubProperties.getValidate().getEnable()) {
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

        applyValidateResult(items, cacheMap);
        cart.recalculate();
    }

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
            applySingleResult(targetItem, r);
            cacheResult(bizType, r);
            cart.recalculate();
            saveUpdatedCart(tenantId, bizType, userId, targetItem);
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

    private List<ProductValidateResult> remoteValidate(String tenantId, String bizType,
                                                        List<ProductValidateDTO> list) {
        String validateUrl = cartHubProperties.getValidate().getValidateUrl();
        if (StringUtils.isBlank(validateUrl)) {
            log.warn("validateUrl not configured, skip remote validation");
            return new ArrayList<>();
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenantId);
            body.put("bizType", bizType);
            body.put("products", list);

            HttpRequest request = HttpUtil.createPost(validateUrl)
                    .header("Content-Type", "application/json")
                    .header(CartConstant.HEADER_TENANT_ID, tenantId)
                    .header(CartConstant.HEADER_BIZ_TYPE, bizType)
                    .timeout(cartHubProperties.getValidate().getTimeoutMs())
                    .body(JSON.toJSONString(body));

            HttpResponse response = request.execute();
            if (!response.isOk()) {
                log.warn("validate request failed, status={}", response.getStatus());
                return new ArrayList<>();
            }
            JSONObject json = JSON.parseObject(response.body());
            if (json == null || json.getInteger("code") != null && json.getInteger("code") != 200) {
                log.warn("validate response error, body={}", response.body());
                return new ArrayList<>();
            }
            String dataStr = json.getString("data");
            if (StringUtils.isBlank(dataStr)) {
                return new ArrayList<>();
            }
            return JSON.parseArray(dataStr, ProductValidateResult.class);
        } catch (Exception e) {
            log.error("remote validate exception", e);
            return new ArrayList<>();
        }
    }

    private void applyValidateResult(List<CartItem> items, Map<String, ProductValidateResult> resultMap) {
        for (CartItem item : items) {
            ProductValidateResult r = resultMap.get(item.getSkuId());
            if (r != null) {
                applySingleResult(item, r);
            }
        }
    }

    private void applySingleResult(CartItem item, ProductValidateResult r) {
        if (r.getStock() != null) {
            item.setStock(r.getStock());
        }
        if (r.getOnShelf() != null) {
            item.setOnShelf(r.getOnShelf());
        }
        if (!Boolean.TRUE.equals(r.getValid())) {
            item.setInvalidMessage(StringUtils.defaultIfBlank(r.getErrorMessage(), "商品不可购买"));
        } else {
            item.setInvalidMessage(null);
        }
        if (r.getCurrentPrice() != null && item.getUnitPrice() != null
                && r.getCurrentPrice().compareTo(item.getUnitPrice()) != 0) {
            item.setOldPrice(item.getUnitPrice());
            item.setUnitPrice(r.getCurrentPrice());
            item.setPriceChanged(true);
        }
        if (Boolean.TRUE.equals(r.getValid())) {
            item.recalculate();
        }
    }

    private void saveUpdatedCart(String tenantId, String bizType, String userId, CartItem item) {
        String cartKey = RedisKeyConstant.buildCartKey(tenantId, bizType, userId);
        org.redisson.api.RMap<String, String> cartMap =
                ((org.redisson.api.RedissonClient) Objects.requireNonNull(redisTemplate.getConnectionFactory()))
                        .getMap(cartKey);
        cartMap.fastPut(item.getSkuId(), JsonUtil.toJson(item));
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
        int expire = cartHubProperties.getValidate().getCacheSeconds();
        stringRedisTemplate.opsForValue().set(key, JsonUtil.toJson(result), Duration.ofSeconds(expire));
    }

    @Async
    public void asyncRevalidateUsers(String tenantId, String bizType, Set<String> userIds) {
        log.info("asyncRevalidateUsers start: tenantId={}, bizType={}, userCount={}", tenantId, bizType, userIds.size());
        int processed = 0;
        for (String userId : userIds) {
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException ignored) {
            }
            processed++;
            if (log.isDebugEnabled()) {
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
