package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.*;
import com.carhub.domain.model.Cart;
import com.carhub.domain.vo.CartVO;
import com.carhub.service.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@Api(tags = "购物车标准API")
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@Validated
public class CartController {

    private final CartService cartService;
    private final CartShareService cartShareService;
    private final CartSnapshotService cartSnapshotService;
    private final CartDiscountService cartDiscountService;

    @ApiOperation("添加商品到购物车")
    @PostMapping("/item")
    public R<Boolean> addItem(@RequestBody @Valid AddCartItemDTO dto) {
        return R.ok(cartService.addItem(dto));
    }

    @ApiOperation("修改购物车商品")
    @PutMapping("/item")
    public R<Boolean> updateItem(@RequestBody @Valid UpdateCartItemDTO dto) {
        return R.ok(cartService.updateItem(dto));
    }

    @ApiOperation("增减商品数量")
    @PatchMapping("/item/quantity")
    public R<Long> incrementQuantity(
            @ApiParam("SKU ID") @RequestParam @NotBlank String skuId,
            @ApiParam("增量，正数增加，负数减少") @RequestParam(defaultValue = "1") int delta) {
        return R.ok(cartService.incrementQuantity(skuId, delta));
    }

    @ApiOperation("删除购物车单个商品")
    @DeleteMapping("/item")
    public R<Boolean> removeItem(@ApiParam("SKU ID") @RequestParam @NotBlank String skuId) {
        return R.ok(cartService.removeItem(skuId));
    }

    @ApiOperation("批量删除购物车商品")
    @DeleteMapping("/items")
    public R<Long> batchRemove(@RequestBody @Valid BatchCartItemDTO dto) {
        return R.ok(cartService.batchRemove(dto));
    }

    @ApiOperation("清空购物车")
    @DeleteMapping("/clear")
    public R<Boolean> clearCart() {
        return R.ok(cartService.clearCart());
    }

    @ApiOperation("获取完整购物车（含校验重算）")
    @GetMapping
    public R<CartVO> getCart(
            @ApiParam("是否执行商品校验") @RequestParam(defaultValue = "true") boolean validate) {
        return R.ok(cartService.getCart(validate));
    }

    @ApiOperation("获取购物车概要（快速，不校验）")
    @GetMapping("/simple")
    public R<CartVO> getCartSimple() {
        return R.ok(cartService.getCartSimple());
    }

    @ApiOperation("获取购物车商品数量")
    @GetMapping("/count")
    public R<Integer> getItemCount() {
        return R.ok(cartService.getItemCount());
    }

    @ApiOperation("获取购物车汇总信息")
    @GetMapping("/summary")
    public R<Map<String, Object>> getCartSummary() {
        return R.ok(cartService.getCartSummary());
    }

    @ApiOperation("合并购物车（登录时用）")
    @PostMapping("/merge")
    public R<CartVO> mergeCart(@RequestBody @Valid MergeCartDTO dto) {
        return R.ok(cartService.mergeCart(dto));
    }

    @ApiOperation("创建购物车分享")
    @PostMapping("/share")
    public R<Map<String, Object>> createShare(
            @ApiParam("分享标题") @RequestParam(required = false) String title,
            @ApiParam("过期时间（小时）") @RequestParam(required = false) Integer expireHours,
            @ApiParam("访问密码") @RequestParam(required = false) String password,
            @ApiParam("分享类型：1-只读，2-可编辑") @RequestParam(required = false) Integer shareType) {
        return R.ok(cartShareService.createShare(title, expireHours, password, shareType));
    }

    @ApiOperation("查看分享的购物车（无需登录）")
    @GetMapping("/share/view/{shareId}")
    public R<Cart> viewShare(
            @ApiParam("分享ID") @PathVariable @NotBlank String shareId,
            @ApiParam("访问密码") @RequestParam(required = false) String password) {
        return R.ok(cartShareService.viewShare(shareId, password));
    }

    @ApiOperation("接受分享（合并到我的购物车）")
    @PostMapping("/share/accept/{shareId}")
    public R<Boolean> acceptShare(
            @ApiParam("分享ID") @PathVariable @NotBlank String shareId,
            @ApiParam("访问密码") @RequestParam(required = false) String password) {
        return R.ok(cartShareService.acceptShare(shareId, password));
    }

    @ApiOperation("查看我创建的分享列表")
    @GetMapping("/share/list")
    public R<List<com.carhub.domain.entity.CartShareEntity>> listMyShares() {
        return R.ok(cartShareService.listMyShares());
    }

    @ApiOperation("取消/删除分享")
    @DeleteMapping("/share/{shareId}")
    public R<Boolean> cancelShare(@PathVariable @NotBlank String shareId) {
        return R.ok(cartShareService.cancelShare(shareId));
    }

    @ApiOperation("创建购物车快照")
    @PostMapping("/snapshot")
    public R<Map<String, Object>> createSnapshot(
            @ApiParam("快照名称") @RequestParam(required = false) String snapshotName,
            @ApiParam("快照类型:manual/auto/share/order") @RequestParam(defaultValue = "manual") String snapshotType,
            @ApiParam("关联订单号") @RequestParam(required = false) String orderNo) {
        return R.ok(cartSnapshotService.createSnapshot(snapshotName, snapshotType, orderNo));
    }

    @ApiOperation("查看快照详情")
    @GetMapping("/snapshot/{snapshotId}")
    public R<Cart> getSnapshot(@PathVariable @NotBlank String snapshotId) {
        return R.ok(cartSnapshotService.getSnapshot(snapshotId));
    }

    @ApiOperation("恢复快照到当前购物车")
    @PostMapping("/snapshot/restore/{snapshotId}")
    public R<Boolean> restoreSnapshot(@PathVariable @NotBlank String snapshotId) {
        return R.ok(cartSnapshotService.restoreSnapshot(snapshotId));
    }

    @ApiOperation("查看我的快照列表")
    @GetMapping("/snapshot/list")
    public R<List<com.carhub.domain.entity.CartSnapshotEntity>> listMySnapshots() {
        return R.ok(cartSnapshotService.listMySnapshots());
    }

    @ApiOperation("删除快照")
    @DeleteMapping("/snapshot/{snapshotId}")
    public R<Boolean> deleteSnapshot(@PathVariable @NotBlank String snapshotId) {
        return R.ok(cartSnapshotService.deleteSnapshot(snapshotId));
    }

    @ApiOperation("应用优惠")
    @PostMapping("/discount")
    public R<Cart> applyDiscount(
            @ApiParam("优惠ID") @RequestParam @NotBlank String discountId,
            @ApiParam("优惠类型:coupon/promotion/vip") @RequestParam @NotBlank String discountType,
            @ApiParam("优惠名称") @RequestParam @NotBlank String discountName,
            @ApiParam("优惠码") @RequestParam(required = false) String discountCode,
            @ApiParam("优惠金额") @RequestParam java.math.BigDecimal discountAmount,
            @ApiParam("适用SKU列表") @RequestParam(required = false) List<String> applySkus,
            @ApiParam("适用范围:all/item") @RequestParam(defaultValue = "all") String scope) {
        return R.ok(cartDiscountService.applyDiscount(discountId, discountType, discountName,
                discountCode, discountAmount, applySkus, scope));
    }

    @ApiOperation("移除优惠")
    @DeleteMapping("/discount/{discountId}")
    public R<Cart> removeDiscount(@PathVariable @NotBlank String discountId) {
        return R.ok(cartDiscountService.removeDiscount(discountId));
    }

    @ApiOperation("查看已应用优惠列表")
    @GetMapping("/discount/list")
    public R<List<com.carhub.domain.model.CartDiscount>> listDiscounts() {
        return R.ok(cartDiscountService.listDiscounts());
    }

    @ApiOperation("清空所有优惠")
    @DeleteMapping("/discount/clear")
    public R<Cart> clearDiscounts() {
        return R.ok(cartDiscountService.clearDiscounts());
    }

}
