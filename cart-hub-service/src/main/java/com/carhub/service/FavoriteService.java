package com.carhub.service;

import com.carhub.common.context.CartContextHolder;
import com.carhub.common.exception.BusinessException;
import com.carhub.common.result.ResultCode;
import com.carhub.common.util.JsonUtil;
import com.carhub.domain.dto.AddCartItemDTO;
import com.carhub.domain.dto.FavoriteItemDTO;
import com.carhub.domain.entity.FavoriteItemEntity;
import com.carhub.domain.model.FavoriteItem;
import com.carhub.domain.vo.CartVO;
import com.carhub.domain.vo.FavoriteVO;
import com.carhub.mapper.FavoriteItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteItemMapper favoriteItemMapper;
    private final CartService cartService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public FavoriteVO getFavorite() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            return buildEmptyVO(tenantId, bizType, userId);
        }

        List<FavoriteItemEntity> entities = favoriteItemMapper.findByUser(tenantId, bizType, userId);
        List<FavoriteItem> items = entities.stream()
                .map(this::convertToModel)
                .collect(Collectors.toList());

        return buildVO(tenantId, bizType, userId, items);
    }

    public FavoriteItem getFavoriteItem(String skuId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId) || StringUtils.isBlank(skuId)) {
            return null;
        }

        FavoriteItemEntity entity = favoriteItemMapper.findBySku(tenantId, bizType, userId, skuId);
        if (entity == null) {
            return null;
        }

        return convertToModel(entity);
    }

    public boolean isFavorited(String skuId) {
        return getFavoriteItem(skuId) != null;
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO addItem(FavoriteItemDTO dto) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        checkUserLoggedIn(userId);
        validateItem(dto);

        FavoriteItemEntity existEntity = favoriteItemMapper.findBySku(tenantId, bizType, userId, dto.getSkuId());
        if (existEntity != null) {
            log.info("Item already in favorite, skip: userId={}, skuId={}", userId, dto.getSkuId());
            return getFavorite();
        }

        FavoriteItemEntity entity = new FavoriteItemEntity();
        entity.setTenantId(tenantId);
        entity.setBizType(bizType);
        entity.setUserId(userId);
        entity.setSkuId(dto.getSkuId());
        entity.setSpuId(dto.getSpuId());
        entity.setCategoryId(dto.getCategoryId());
        entity.setShopId(dto.getShopId());
        entity.setItemName(dto.getItemName() != null ? dto.getItemName() : "商品-" + dto.getSkuId());
        entity.setItemImage(dto.getItemImage());
        entity.setItemSpec(dto.getItemSpec() != null ? JsonUtil.toJson(dto.getItemSpec()) : null);
        entity.setUnitPrice(dto.getUnitPrice());
        entity.setOriginalPrice(dto.getOriginalPrice());
        entity.setOnShelf(1);
        entity.setPriceChanged(0);
        entity.setAddTime(System.currentTimeMillis());
        entity.setAddSource(StringUtils.defaultIfBlank(dto.getAddSource(), CartContextHolder.getSource()));
        if (dto.getExtInfo() != null) {
            entity.setExtInfo(JsonUtil.toJson(dto.getExtInfo()));
        }

        favoriteItemMapper.insert(entity);
        log.info("Favorite item added: userId={}, skuId={}, price={}", userId, dto.getSkuId(), dto.getUnitPrice());

        return getFavorite();
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO addItems(List<FavoriteItemDTO> items) {
        if (items == null || items.isEmpty()) {
            return getFavorite();
        }

        for (FavoriteItemDTO item : items) {
            try {
                addItem(item);
            } catch (Exception e) {
                log.warn("Batch add favorite item failed: skuId={}, error={}", item.getSkuId(), e.getMessage());
            }
        }

        return getFavorite();
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO removeItem(String skuId) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        checkUserLoggedIn(userId);

        if (StringUtils.isBlank(skuId)) {
            throw new BusinessException("商品ID不能为空");
        }

        FavoriteItemEntity entity = favoriteItemMapper.findBySku(tenantId, bizType, userId, skuId);
        if (entity != null) {
            favoriteItemMapper.deleteById(entity.getId());
            log.info("Favorite item removed: userId={}, skuId={}", userId, skuId);
        }

        return getFavorite();
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO removeItems(List<String> skuIds) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        checkUserLoggedIn(userId);

        if (skuIds == null || skuIds.isEmpty()) {
            return getFavorite();
        }

        favoriteItemMapper.deleteBySkus(tenantId, bizType, userId, skuIds);
        log.info("Batch remove favorite items: userId={}, count={}", userId, skuIds.size());

        return getFavorite();
    }

    @Transactional(rollbackFor = Exception.class)
    public FavoriteVO clear() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        checkUserLoggedIn(userId);

        List<FavoriteItemEntity> entities = favoriteItemMapper.findByUser(tenantId, bizType, userId);
        for (FavoriteItemEntity entity : entities) {
            favoriteItemMapper.deleteById(entity.getId());
        }

        log.info("Favorite cleared: userId={}, count={}", userId, entities.size());

        return getFavorite();
    }

    public Integer getCount() {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        if (StringUtils.isBlank(userId)) {
            return 0;
        }

        List<FavoriteItemEntity> entities = favoriteItemMapper.findByUser(tenantId, bizType, userId);
        return entities.size();
    }

    @Transactional(rollbackFor = Exception.class)
    public CartVO addToCart(List<String> skuIds, boolean removeFromFavorite) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        checkUserLoggedIn(userId);

        if (skuIds == null || skuIds.isEmpty()) {
            throw new BusinessException("请选择要加入购物车的商品");
        }

        List<FavoriteItemEntity> entities = favoriteItemMapper.findByUser(tenantId, bizType, userId);
        Map<String, FavoriteItemEntity> entityMap = entities.stream()
                .collect(Collectors.toMap(FavoriteItemEntity::getSkuId, e -> e));

        List<String> successSkuIds = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String skuId : skuIds) {
            try {
                FavoriteItemEntity entity = entityMap.get(skuId);
                if (entity == null) {
                    failCount++;
                    continue;
                }

                AddCartItemDTO addDTO = AddCartItemDTO.builder()
                        .skuId(entity.getSkuId())
                        .spuId(entity.getSpuId())
                        .categoryId(entity.getCategoryId())
                        .shopId(entity.getShopId())
                        .itemName(entity.getItemName())
                        .itemImage(entity.getItemImage())
                        .itemSpec(entity.getItemSpec() != null ? JsonUtil.fromJson(entity.getItemSpec(), Map.class) : null)
                        .unitPrice(entity.getUnitPrice())
                        .originalPrice(entity.getOriginalPrice())
                        .quantity(1)
                        .selected(true)
                        .addSource("favorite")
                        .build();

                cartService.addItem(addDTO);
                successSkuIds.add(skuId);
                successCount++;

                log.info("Favorite to cart success: userId={}, skuId={}", userId, skuId);
            } catch (Exception e) {
                failCount++;
                log.warn("Favorite to cart failed: skuId={}, error={}", skuId, e.getMessage());
            }
        }

        if (removeFromFavorite && !successSkuIds.isEmpty()) {
            favoriteItemMapper.deleteBySkus(tenantId, bizType, userId, successSkuIds);
            log.info("Removed from favorite after adding to cart: userId={}, count={}", userId, successSkuIds.size());
        }

        if (successCount == 0) {
            throw new BusinessException("加入购物车失败，请检查商品是否有效");
        }

        CartVO cartVO = cartService.getCartSimple();
        cartVO.setAddSuccessCount(successCount);
        cartVO.setAddFailCount(failCount);

        return cartVO;
    }

    @Transactional(rollbackFor = Exception.class)
    public CartVO addAllToCart(boolean removeFromFavorite) {
        String tenantId = CartContextHolder.getTenantId();
        String bizType = CartContextHolder.getBizType();
        String userId = CartContextHolder.getUserId();

        checkUserLoggedIn(userId);

        List<FavoriteItemEntity> entities = favoriteItemMapper.findByUser(tenantId, bizType, userId);
        if (entities.isEmpty()) {
            throw new BusinessException("收藏夹为空");
        }

        List<String> skuIds = entities.stream()
                .map(FavoriteItemEntity::getSkuId)
                .collect(Collectors.toList());

        return addToCart(skuIds, removeFromFavorite);
    }

    private void checkUserLoggedIn(String userId) {
        if (StringUtils.isBlank(userId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "请先登录");
        }
    }

    private void validateItem(FavoriteItemDTO dto) {
        if (StringUtils.isBlank(dto.getSkuId())) {
            throw new BusinessException("商品SKU ID不能为空");
        }
        if (dto.getUnitPrice() == null) {
            throw new BusinessException("商品单价不能为空");
        }
        if (dto.getUnitPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("商品单价不能为负数");
        }
    }

    private FavoriteItem convertToModel(FavoriteItemEntity entity) {
        return FavoriteItem.builder()
                .skuId(entity.getSkuId())
                .spuId(entity.getSpuId())
                .categoryId(entity.getCategoryId())
                .shopId(entity.getShopId())
                .itemName(entity.getItemName())
                .itemImage(entity.getItemImage())
                .itemSpec(entity.getItemSpec() != null ? JsonUtil.fromJson(entity.getItemSpec(), Map.class) : null)
                .unitPrice(entity.getUnitPrice())
                .originalPrice(entity.getOriginalPrice())
                .onShelf(entity.getOnShelf() != null && entity.getOnShelf() == 1)
                .priceChanged(entity.getPriceChanged() != null && entity.getPriceChanged() == 1)
                .addTime(entity.getAddTime())
                .addSource(entity.getAddSource())
                .extInfo(entity.getExtInfo() != null ? JsonUtil.fromJson(entity.getExtInfo(), Map.class) : null)
                .build();
    }

    private FavoriteVO buildVO(String tenantId, String bizType, String userId, List<FavoriteItem> items) {
        BigDecimal totalAmount = items.stream()
                .filter(i -> i.getUnitPrice() != null)
                .map(FavoriteItem::getUnitPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long maxAddTime = items.stream()
                .filter(i -> i.getAddTime() != null)
                .mapToLong(FavoriteItem::getAddTime)
                .max()
                .orElse(System.currentTimeMillis());

        return FavoriteVO.builder()
                .tenantId(tenantId)
                .bizType(bizType)
                .userId(userId)
                .items(items)
                .itemCount(items.size())
                .totalAmount(totalAmount)
                .updateTime(maxAddTime)
                .build();
    }

    private FavoriteVO buildEmptyVO(String tenantId, String bizType, String userId) {
        return FavoriteVO.builder()
                .tenantId(tenantId)
                .bizType(bizType)
                .userId(userId)
                .items(Collections.emptyList())
                .itemCount(0)
                .totalAmount(BigDecimal.ZERO)
                .updateTime(System.currentTimeMillis())
                .build();
    }

}
