package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.FavoriteItemDTO;
import com.carhub.domain.vo.CartVO;
import com.carhub.domain.vo.FavoriteVO;
import com.carhub.service.FavoriteService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

@Api(tags = "商品收藏夹API")
@RestController
@RequestMapping("/api/favorite")
@RequiredArgsConstructor
@Validated
public class FavoriteController {

    private final FavoriteService favoriteService;

    @ApiOperation("获取收藏夹列表")
    @GetMapping
    public R<FavoriteVO> getFavorite() {
        return R.ok(favoriteService.getFavorite());
    }

    @ApiOperation("判断商品是否已收藏")
    @GetMapping("/check/{skuId}")
    public R<Boolean> isFavorited(@ApiParam("商品SKU ID") @PathVariable @NotBlank String skuId) {
        return R.ok(favoriteService.isFavorited(skuId));
    }

    @ApiOperation("获取收藏夹商品数量")
    @GetMapping("/count")
    public R<Integer> getCount() {
        return R.ok(favoriteService.getCount());
    }

    @ApiOperation("添加商品到收藏夹")
    @PostMapping("/item")
    public R<FavoriteVO> addItem(@RequestBody @Valid FavoriteItemDTO dto) {
        return R.ok(favoriteService.addItem(dto));
    }

    @ApiOperation("批量添加商品到收藏夹")
    @PostMapping("/items")
    public R<FavoriteVO> addItems(@RequestBody @NotEmpty List<FavoriteItemDTO> items) {
        return R.ok(favoriteService.addItems(items));
    }

    @ApiOperation("移除收藏夹商品")
    @DeleteMapping("/item/{skuId}")
    public R<FavoriteVO> removeItem(@ApiParam("商品SKU ID") @PathVariable @NotBlank String skuId) {
        return R.ok(favoriteService.removeItem(skuId));
    }

    @ApiOperation("批量移除收藏夹商品")
    @DeleteMapping("/items")
    public R<FavoriteVO> removeItems(@RequestBody @NotEmpty List<String> skuIds) {
        return R.ok(favoriteService.removeItems(skuIds));
    }

    @ApiOperation("清空收藏夹")
    @DeleteMapping("/clear")
    public R<FavoriteVO> clear() {
        return R.ok(favoriteService.clear());
    }

    @ApiOperation("收藏夹商品一键加入购物车")
    @PostMapping("/cart/add")
    public R<CartVO> addToCart(
            @RequestBody Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        List<String> skuIds = (List<String>) params.get("skuIds");
        Boolean removeFromFavorite = params.get("removeFromFavorite") != null
                ? (Boolean) params.get("removeFromFavorite") : false;
        return R.ok(favoriteService.addToCart(skuIds, removeFromFavorite));
    }

    @ApiOperation("收藏夹全部商品加入购物车")
    @PostMapping("/cart/add-all")
    public R<CartVO> addAllToCart(
            @RequestBody(required = false) Map<String, Object> params) {
        boolean removeFromFavorite = params != null && params.get("removeFromFavorite") != null
                ? (Boolean) params.get("removeFromFavorite") : false;
        return R.ok(favoriteService.addAllToCart(removeFromFavorite));
    }

}
