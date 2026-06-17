package com.carhub.service;

import com.carhub.common.constant.AnalyticsConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.dto.InventoryCheckDTO;
import com.carhub.domain.dto.InventoryCheckDTO.InventoryItemDTO;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.InventoryCheckVO;
import com.carhub.domain.vo.InventoryCheckVO.InventoryItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final CartHubProperties cartHubProperties;
    private final RestTemplate restTemplate;
    private final CartAnalyticsService cartAnalyticsService;

    @Resource
    private com.carhub.storage.CartRedisStorage cartRedisStorage;

    @PostConstruct
    public void init() {
        log.info("InventoryService initialized, stockCheckUrl={}, enableStockCheck={}",
                cartHubProperties.getCheckout().getStockCheckUrl(),
                cartHubProperties.getCheckout().getEnableStockCheck());
    }

    public InventoryCheckVO checkInventory(List<InventoryItemDTO> items) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();

        if (items == null || items.isEmpty()) {
            return InventoryCheckVO.builder()
                    .allAvailable(true)
                    .hasShortage(false)
                    .items(Collections.emptyList())
                    .shortageItems(Collections.emptyList())
                    .build();
        }

        List<InventoryItemVO> itemResults;

        if (Boolean.TRUE.equals(cartHubProperties.getCheckout().getMockStock())
                && StringUtils.isBlank(cartHubProperties.getCheckout().getStockCheckUrl())) {
            itemResults = mockCheckInventory(items);
        } else {
            itemResults = callStockCheckApi(tenantId, bizType, items);
        }

        List<InventoryItemVO> shortageItems = itemResults.stream()
                .filter(item -> !item.isAvailable())
                .collect(Collectors.toList());

        boolean allAvailable = shortageItems.isEmpty();

        if (!allAvailable) {
            trackInventoryShortage(tenantId, bizType, shortageItems);
        }

        return InventoryCheckVO.builder()
                .allAvailable(allAvailable)
                .hasShortage(!allAvailable)
                .items(itemResults)
                .shortageItems(shortageItems)
                .build();
    }

    public InventoryCheckVO checkCartInventory(String userId, boolean autoDeselect) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();

        if (StringUtils.isBlank(userId)) {
            userId = CartContextHolder.getUserId();
        }

        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            return InventoryCheckVO.builder()
                    .allAvailable(true)
                    .hasShortage(false)
                    .items(Collections.emptyList())
                    .shortageItems(Collections.emptyList())
                    .build();
        }

        List<InventoryItemDTO> items = cart.getItems().stream()
                .filter(item -> Boolean.TRUE.equals(item.getSelected()))
                .map(item -> InventoryItemDTO.builder()
                        .skuId(item.getSkuId())
                        .spuId(item.getSpuId())
                        .quantity(item.getQuantity())
                        .itemName(item.getItemName())
                        .build())
                .collect(Collectors.toList());

        InventoryCheckVO result = checkInventory(items);

        if (autoDeselect && result.isHasShortage()) {
            deselectShortageItems(tenantId, bizType, userId, cart, result.getShortageItems());
        }

        enrichItemDetails(cart, result);

        return result;
    }

    private List<InventoryItemVO> callStockCheckApi(String tenantId, String bizType,
                                                    List<InventoryItemDTO> items) {
        String stockCheckUrl = cartHubProperties.getCheckout().getStockCheckUrl();
        if (StringUtils.isBlank(stockCheckUrl)) {
            log.warn("Stock check URL not configured, fallback to mock");
            return mockCheckInventory(items);
        }

        try {
            Map<String, Object> request = new HashMap<>();
            request.put("tenantId", tenantId);
            request.put("bizType", bizType);
            request.put("items", items);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    stockCheckUrl, entity, Map.class);

            Map<String, Object> body = response.getBody();
            if (body != null && Boolean.TRUE.equals(body.get("success"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> resultList = (List<Map<String, Object>>) body.get("items");
                if (resultList != null) {
                    return convertToItemVO(items, resultList);
                }
            } else {
                String message = body != null ? (String) body.get("message") : "库存校验失败";
                log.warn("Stock check API returned error: {}", message);
            }
        } catch (Exception e) {
            log.error("Call stock check API failed", e);
            if (Boolean.TRUE.equals(cartHubProperties.getCheckout().getMockStock())) {
                log.warn("Fallback to mock stock check due to API failure");
                return mockCheckInventory(items);
            }
        }

        return mockCheckInventory(items);
    }

    private List<InventoryItemVO> convertToItemVO(List<InventoryItemDTO> requestItems,
                                                   List<Map<String, Object>> apiResults) {
        Map<String, Map<String, Object>> resultMap = new HashMap<>();
        for (Map<String, Object> result : apiResults) {
            String skuId = String.valueOf(result.get("skuId"));
            resultMap.put(skuId, result);
        }

        List<InventoryItemVO> result = new ArrayList<>();
        for (InventoryItemDTO requestItem : requestItems) {
            Map<String, Object> apiResult = resultMap.get(requestItem.getSkuId());

            InventoryItemVO.InventoryItemVOBuilder builder = InventoryItemVO.builder()
                    .skuId(requestItem.getSkuId())
                    .spuId(requestItem.getSpuId())
                    .itemName(requestItem.getItemName())
                    .requestedQuantity(requestItem.getQuantity());

            if (apiResult != null) {
                boolean available = Boolean.TRUE.equals(apiResult.get("available"));
                Integer stock = apiResult.get("stock") != null ?
                        Integer.valueOf(String.valueOf(apiResult.get("stock"))) : null;
                Integer availableQuantity = apiResult.get("availableQuantity") != null ?
                        Integer.valueOf(String.valueOf(apiResult.get("availableQuantity"))) : null;

                builder.available(available)
                        .stock(stock)
                        .availableQuantity(availableQuantity);

                if (!available) {
                    String reason = (String) apiResult.get("reason");
                    if (StringUtils.isBlank(reason)) {
                        if (stock != null && requestItem.getQuantity() != null && stock < requestItem.getQuantity()) {
                            reason = "库存不足，仅剩 " + stock + " 件";
                        } else {
                            reason = "库存不足";
                        }
                    }
                    builder.shortageReason(reason);
                }

                if (apiResult.get("unitPrice") != null) {
                    builder.unitPrice(new BigDecimal(String.valueOf(apiResult.get("unitPrice"))));
                }
                if (apiResult.get("itemImage") != null) {
                    builder.itemImage(String.valueOf(apiResult.get("itemImage")));
                }
                if (apiResult.get("categoryId") != null) {
                    builder.categoryId(String.valueOf(apiResult.get("categoryId")));
                }
                if (apiResult.get("categoryName") != null) {
                    builder.categoryName(String.valueOf(apiResult.get("categoryName")));
                }
                if (apiResult.get("shopId") != null) {
                    builder.shopId(String.valueOf(apiResult.get("shopId")));
                }
            } else {
                builder.available(true)
                        .stock(requestItem.getQuantity() + 100)
                        .availableQuantity(requestItem.getQuantity() + 100);
            }

            result.add(builder.build());
        }

        return result;
    }

    private List<InventoryItemVO> mockCheckInventory(List<InventoryItemDTO> items) {
        List<InventoryItemVO> result = new ArrayList<>();

        for (InventoryItemDTO item : items) {
            boolean available;
            String shortageReason = null;
            int stock;

            if (item.getSkuId() != null && item.getSkuId().endsWith("_SHORTAGE")) {
                available = false;
                stock = new Random().nextInt(Math.max(1, item.getQuantity() - 1));
                shortageReason = "库存不足，仅剩 " + stock + " 件";
            } else {
                available = new Random().nextInt(100) < 85;
                if (available) {
                    stock = item.getQuantity() + new Random().nextInt(500);
                } else {
                    stock = new Random().nextInt(Math.max(1, item.getQuantity()));
                    shortageReason = "库存不足，仅剩 " + stock + " 件";
                }
            }

            result.add(InventoryItemVO.builder()
                    .skuId(item.getSkuId())
                    .spuId(item.getSpuId())
                    .itemName(item.getItemName())
                    .requestedQuantity(item.getQuantity())
                    .stock(stock)
                    .availableQuantity(stock)
                    .available(available)
                    .shortageReason(shortageReason)
                    .build());
        }

        log.info("Mock inventory check completed, items={}, available={}/{}",
                items.size(),
                result.stream().filter(InventoryItemVO::isAvailable).count(),
                result.size());

        return result;
    }

    private void enrichItemDetails(Cart cart, InventoryCheckVO result) {
        if (cart == null || cart.getItems() == null || result == null || result.getItems() == null) {
            return;
        }

        Map<String, CartItem> cartItemMap = cart.getItems().stream()
                .collect(Collectors.toMap(CartItem::getSkuId, item -> item, (a, b) -> a));

        for (InventoryItemVO itemVO : result.getItems()) {
            CartItem cartItem = cartItemMap.get(itemVO.getSkuId());
            if (cartItem != null) {
                if (StringUtils.isBlank(itemVO.getItemName())) {
                    itemVO.setItemName(cartItem.getItemName());
                }
                if (StringUtils.isBlank(itemVO.getItemImage())) {
                    itemVO.setItemImage(cartItem.getItemImage());
                }
                if (itemVO.getUnitPrice() == null) {
                    itemVO.setUnitPrice(cartItem.getUnitPrice());
                }
                if (StringUtils.isBlank(itemVO.getCategoryId())) {
                    itemVO.setCategoryId(cartItem.getCategoryId());
                }
                if (StringUtils.isBlank(itemVO.getShopId())) {
                    itemVO.setShopId(cartItem.getShopId());
                }
            }
        }

        if (result.getShortageItems() != null) {
            for (InventoryItemVO itemVO : result.getShortageItems()) {
                CartItem cartItem = cartItemMap.get(itemVO.getSkuId());
                if (cartItem != null) {
                    if (StringUtils.isBlank(itemVO.getItemName())) {
                        itemVO.setItemName(cartItem.getItemName());
                    }
                    if (StringUtils.isBlank(itemVO.getItemImage())) {
                        itemVO.setItemImage(cartItem.getItemImage());
                    }
                    if (itemVO.getUnitPrice() == null) {
                        itemVO.setUnitPrice(cartItem.getUnitPrice());
                    }
                }
            }
        }
    }

    private void deselectShortageItems(String tenantId, String bizType, String userId,
                                       Cart cart, List<InventoryItemVO> shortageItems) {
        if (shortageItems == null || shortageItems.isEmpty() || cart.getItems() == null) {
            return;
        }

        Set<String> shortageSkuIds = shortageItems.stream()
                .map(InventoryItemVO::getSkuId)
                .collect(Collectors.toSet());

        for (CartItem item : cart.getItems()) {
            if (shortageSkuIds.contains(item.getSkuId())) {
                item.setSelected(false);
                item.setInvalidMessage("库存不足");
            }
        }

        cart.recalculate();
        cartRedisStorage.saveCart(tenantId, bizType, userId, cart);

        log.info("Deselected {} shortage items for user: {}", shortageItems.size(), userId);
    }

    private void trackInventoryShortage(String tenantId, String bizType, List<InventoryItemVO> shortageItems) {
        try {
            Map<String, Object> eventProps = new HashMap<>();
            eventProps.put("shortageCount", shortageItems.size());

            List<Map<String, Object>> shortageDetails = new ArrayList<>();
            for (InventoryItemVO item : shortageItems) {
                Map<String, Object> detail = new HashMap<>();
                detail.put("skuId", item.getSkuId());
                detail.put("spuId", item.getSpuId());
                detail.put("itemName", item.getItemName());
                detail.put("categoryId", item.getCategoryId());
                detail.put("categoryName", item.getCategoryName());
                detail.put("requestedQuantity", item.getRequestedQuantity());
                detail.put("availableQuantity", item.getAvailableQuantity());
                detail.put("stock", item.getStock());
                detail.put("shortageReason", item.getShortageReason());
                detail.put("unitPrice", item.getUnitPrice());
                shortageDetails.add(detail);
            }
            eventProps.put("items", shortageDetails);

            if (!shortageItems.isEmpty()) {
                InventoryItemVO firstItem = shortageItems.get(0);
                eventProps.put("skuId", firstItem.getSkuId());
                eventProps.put("spuId", firstItem.getSpuId());
                eventProps.put("categoryId", firstItem.getCategoryId());
                eventProps.put("categoryName", firstItem.getCategoryName());
                eventProps.put("itemName", firstItem.getItemName());
            }

            com.carhub.domain.dto.CartEventDTO event = com.carhub.domain.dto.CartEventDTO.builder()
                    .eventType(AnalyticsConstant.EVENT_INVENTORY_SHORTAGE_WARNING)
                    .tenantId(tenantId)
                    .bizType(bizType)
                    .userId(CartContextHolder.getUserId())
                    .source(CartContextHolder.getSource())
                    .properties(eventProps)
                    .build();

            if (!shortageItems.isEmpty()) {
                event.setSkuId(shortageItems.get(0).getSkuId());
                event.setSpuId(shortageItems.get(0).getSpuId());
                event.setCategoryId(shortageItems.get(0).getCategoryId());
                event.setCategoryName(shortageItems.get(0).getCategoryName());
                event.setItemName(shortageItems.get(0).getItemName());
                event.setQuantity(shortageItems.get(0).getRequestedQuantity());
                event.setStock(shortageItems.get(0).getStock());
                event.setInvalidMessage(shortageItems.get(0).getShortageReason());
            }

            cartAnalyticsService.trackEvent(event);
        } catch (Exception e) {
            log.warn("Track inventory shortage event failed", e);
        }
    }
}
