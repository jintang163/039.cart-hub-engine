package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.*;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.CartVO;
import com.carhub.domain.vo.CouponVO;
import com.carhub.domain.vo.DiscountResultVO;
import com.carhub.domain.vo.PromotionVO;
import com.carhub.domain.vo.RecommendItemVO;
import com.carhub.service.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
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
    private final CartPromotionService cartPromotionService;
    private final PromotionEngineService promotionEngineService;
    private final CartRecommendService cartRecommendService;
    private final CartExpireCleanupService cartExpireCleanupService;
    private final CartPriceDropNotifyService cartPriceDropNotifyService;
    private final CartImportExportService cartImportExportService;

    @ApiOperation("添加商品到购物车")
    @PostMapping("/item")
    public R<CartVO> addItem(@RequestBody @Valid AddCartItemDTO dto) {
        return R.ok(cartService.addItem(dto));
    }

    @ApiOperation("修改购物车商品")
    @PutMapping("/item")
    public R<CartVO> updateItem(@RequestBody @Valid UpdateCartItemDTO dto) {
        return R.ok(cartService.updateItem(dto));
    }

    @ApiOperation("增减商品数量")
    @PatchMapping("/item/quantity")
    public R<CartVO> incrementQuantity(
            @ApiParam("SKU ID") @RequestParam @NotBlank String skuId,
            @ApiParam("增量，正数增加，负数减少") @RequestParam(defaultValue = "1") int delta,
            @ApiParam("客户端版本号") @RequestParam(required = false) Long clientVersion,
            @ApiParam("是否强制覆盖") @RequestParam(required = false, defaultValue = "false") Boolean forceOverwrite,
            @ApiParam("客户端本地商品列表（用于冲突差异比对）") @RequestBody(required = false) List<CartItem> clientItems) {
        return R.ok(cartService.incrementQuantity(skuId, delta, clientVersion, forceOverwrite, clientItems));
    }

    @ApiOperation("删除购物车单个商品")
    @DeleteMapping("/item")
    public R<CartVO> removeItem(
            @ApiParam("SKU ID") @RequestParam @NotBlank String skuId,
            @ApiParam("客户端版本号") @RequestParam(required = false) Long clientVersion,
            @ApiParam("是否强制覆盖") @RequestParam(required = false, defaultValue = "false") Boolean forceOverwrite,
            @ApiParam("客户端本地商品列表（用于冲突差异比对）") @RequestBody(required = false) List<CartItem> clientItems) {
        return R.ok(cartService.removeItem(skuId, clientVersion, forceOverwrite, clientItems));
    }

    @ApiOperation("批量删除购物车商品")
    @DeleteMapping("/items")
    public R<CartVO> batchRemove(@RequestBody @Valid BatchCartItemDTO dto) {
        return R.ok(cartService.batchRemove(dto));
    }

    @ApiOperation("清空购物车")
    @DeleteMapping("/clear")
    public R<CartVO> clearCart(
            @ApiParam("客户端版本号") @RequestParam(required = false) Long clientVersion,
            @ApiParam("是否强制覆盖") @RequestParam(required = false, defaultValue = "false") Boolean forceOverwrite,
            @ApiParam("客户端本地商品列表（用于冲突差异比对）") @RequestBody(required = false) List<CartItem> clientItems) {
        return R.ok(cartService.clearCart(clientVersion, forceOverwrite, clientItems));
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
    public R<Map<String, Object>> acceptShare(
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

    @ApiOperation("使用优惠券码绑定优惠券")
    @PostMapping("/coupon/apply-code")
    public R<Cart> applyCouponCode(@RequestBody @Valid ApplyCouponDTO dto) {
        return R.ok(cartPromotionService.applyCouponCode(dto.getCouponCode()));
    }

    @ApiOperation("使用优惠券ID绑定优惠券")
    @PostMapping("/coupon/apply/{couponId}")
    public R<Cart> applyCoupon(
            @ApiParam("优惠券ID") @PathVariable @NotBlank String couponId) {
        return R.ok(cartPromotionService.applyCoupon(couponId));
    }

    @ApiOperation("移除已选择的优惠券")
    @DeleteMapping("/coupon/remove")
    public R<Cart> removeCoupon() {
        return R.ok(cartPromotionService.removeCoupon());
    }

    @ApiOperation("绑定促销活动")
    @PostMapping("/promotion/apply/{promotionId}")
    public R<Cart> applyPromotion(
            @ApiParam("促销活动ID") @PathVariable @NotBlank String promotionId) {
        return R.ok(cartPromotionService.applyPromotion(promotionId));
    }

    @ApiOperation("移除促销活动")
    @DeleteMapping("/promotion/remove/{promotionId}")
    public R<Cart> removePromotion(
            @ApiParam("促销活动ID") @PathVariable @NotBlank String promotionId) {
        return R.ok(cartPromotionService.removePromotion(promotionId));
    }

    @ApiOperation("重新计算优惠")
    @PostMapping("/discount/recalculate")
    public R<Cart> recalculateDiscount() {
        return R.ok(cartPromotionService.recalculateDiscount());
    }

    @ApiOperation("查询用户可用优惠券列表")
    @GetMapping("/coupon/available")
    public R<List<CouponVO>> listAvailableCoupons(
            @ApiParam("购物车总金额（用于判断是否可用）") @RequestParam(required = false) BigDecimal totalAmount) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        if (totalAmount == null) {
            CartVO cart = cartService.getCartSimple();
            totalAmount = cart.getTotalAmount();
        }
        return R.ok(promotionEngineService.listAvailableCoupons(tenantId, bizType, userId, totalAmount));
    }

    @ApiOperation("查询可用促销活动列表")
    @GetMapping("/promotion/available")
    public R<List<PromotionVO>> listAvailablePromotions(
            @ApiParam("购物车总金额（用于判断是否可用）") @RequestParam(required = false) BigDecimal totalAmount) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        if (totalAmount == null) {
            CartVO cart = cartService.getCartSimple();
            totalAmount = cart.getTotalAmount();
        }
        return R.ok(promotionEngineService.listAvailablePromotions(tenantId, bizType, totalAmount));
    }

    @ApiOperation("获取优惠计算结果")
    @GetMapping("/discount/result")
    public R<DiscountResultVO> getDiscountResult() {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);

        PromotionCalculateDTO calculateDTO = PromotionCalculateDTO.builder()
                .tenantId(tenantId)
                .bizType(bizType)
                .userId(userId)
                .items(cart.getItems())
                .totalAmount(cart.getTotalAmount())
                .selectedCouponId(cart.getSelectedCouponId())
                .couponCode(cart.getCouponCode())
                .selectedPromotionIds(cart.getSelectedPromotionIds())
                .build();

        return R.ok(promotionEngineService.calculateDiscount(calculateDTO));
    }

    @ApiOperation("获取阶梯满减实时进度")
    @GetMapping("/discount/tiered-progress")
    public R<com.carhub.domain.vo.TieredDiscountProgressVO> getTieredDiscountProgress(
            @ApiParam("购物车总金额（不传则自动获取）") @RequestParam(required = false) BigDecimal totalAmount) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        if (totalAmount == null) {
            CartVO cart = cartService.getCartSimple();
            totalAmount = cart.getTotalAmount();
        }
        return R.ok(promotionEngineService.calculateTieredDiscountProgress(tenantId, bizType, totalAmount));
    }

    @ApiOperation("获取购物车智能推荐")
    @GetMapping("/recommend")
    public R<List<RecommendItemVO>> getRecommendations(
            @ApiParam("当前购物车SKU列表") @RequestParam(required = false) List<String> currentSkus,
            @ApiParam("推荐数量") @RequestParam(required = false, defaultValue = "10") Integer topN) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        return R.ok(cartRecommendService.getRecommendations(tenantId, bizType, userId, currentSkus, topN));
    }

    @ApiOperation("手动触发关联分析（管理后台用）")
    @PostMapping("/recommend/analyze")
    public R<String> triggerAnalyze() {
        cartRecommendService.analyzeAllAssociations();
        return R.ok("关联分析已触发");
    }

    @ApiOperation("设置购物车商品备注")
    @PutMapping("/item/remark")
    public R<CartVO> setItemRemark(@RequestBody @Valid UpdateItemRemarkDTO dto) {
        return R.ok(cartService.setItemRemark(dto));
    }

    @ApiOperation("获取购物车单个商品备注")
    @GetMapping("/item/remark")
    public R<String> getItemRemark(
            @ApiParam("SKU ID") @RequestParam @NotBlank String skuId) {
        return R.ok(cartService.getItemRemark(skuId));
    }

    @ApiOperation("获取购物车所有商品备注")
    @GetMapping("/items/remarks")
    public R<Map<String, String>> getAllItemRemarks() {
        return R.ok(cartService.getAllItemRemarks());
    }

    @ApiOperation("删除购物车单个商品备注")
    @DeleteMapping("/item/remark")
    public R<CartVO> removeItemRemark(
            @ApiParam("SKU ID") @RequestParam @NotBlank String skuId,
            @ApiParam("客户端版本号") @RequestParam(required = false) Long clientVersion,
            @ApiParam("是否强制覆盖") @RequestParam(required = false, defaultValue = "false") Boolean forceOverwrite,
            @ApiParam("客户端本地商品列表（用于冲突差异比对）") @RequestBody(required = false) List<CartItem> clientItems) {
        return R.ok(cartService.removeItemRemark(skuId, clientVersion, forceOverwrite, clientItems));
    }

    @ApiOperation("清空购物车所有商品备注")
    @DeleteMapping("/items/remarks")
    public R<CartVO> clearAllItemRemarks(
            @ApiParam("客户端版本号") @RequestParam(required = false) Long clientVersion,
            @ApiParam("是否强制覆盖") @RequestParam(required = false, defaultValue = "false") Boolean forceOverwrite,
            @ApiParam("客户端本地商品列表（用于冲突差异比对）") @RequestBody(required = false) List<CartItem> clientItems) {
        return R.ok(cartService.clearAllItemRemarks(clientVersion, forceOverwrite, clientItems));
    }

    @ApiOperation("批量更新购物车商品排序（拖拽排序用）")
    @PutMapping("/items/sort")
    public R<CartVO> batchSort(@RequestBody @Valid BatchSortDTO dto) {
        return R.ok(cartService.batchSort(dto));
    }

    @ApiOperation("获取购物车过期信息")
    @GetMapping("/expire-info")
    public R<Map<String, Object>> getExpireInfo() {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        return R.ok(cartExpireCleanupService.getExpireInfo(tenantId, bizType, userId));
    }

    @ApiOperation("设置商品降价提醒订阅")
    @PostMapping("/price-drop/subscribe")
    public R<Boolean> subscribePriceDrop(
            @ApiParam("SKU ID") @RequestParam @NotBlank String skuId,
            @ApiParam("目标价格（达到该价格时必提醒）") @RequestParam(required = false) BigDecimal targetPrice) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        return R.ok(cartPriceDropNotifyService.subscribe(tenantId, bizType, userId, skuId, targetPrice));
    }

    @ApiOperation("取消商品降价提醒订阅")
    @DeleteMapping("/price-drop/unsubscribe")
    public R<Boolean> unsubscribePriceDrop(
            @ApiParam("SKU ID") @RequestParam @NotBlank String skuId) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        return R.ok(cartPriceDropNotifyService.unsubscribe(tenantId, bizType, userId, skuId));
    }

    @ApiOperation("批量取消降价提醒订阅（不传SKU列表则取消全部）")
    @DeleteMapping("/price-drop/unsubscribe/batch")
    public R<Boolean> batchUnsubscribePriceDrop(
            @ApiParam("SKU ID 列表") @RequestBody(required = false) List<String> skuIds) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        return R.ok(cartPriceDropNotifyService.batchUnsubscribe(tenantId, bizType, userId, skuIds));
    }

    @ApiOperation("获取我的降价提醒订阅列表（含实时价格对比）")
    @GetMapping("/price-drop/info")
    public R<Map<String, Object>> getPriceDropInfo() {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        String bizType = com.carhub.common.context.CartContextHolder.getBizType();
        String userId = com.carhub.common.context.CartContextHolder.getUserId();
        return R.ok(cartPriceDropNotifyService.getPriceDropInfo(tenantId, bizType, userId));
    }

    @ApiOperation("Excel批量导入商品到购物车")
    @PostMapping("/import")
    public R<com.carhub.domain.vo.ImportTaskVO> importCart(
            @ApiParam("Excel文件") @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @ApiParam("是否校验商品有效性") @RequestParam(defaultValue = "true") Boolean validateProduct,
            @ApiParam("是否覆盖已存在商品") @RequestParam(defaultValue = "false") Boolean overwrite,
            @ApiParam("添加来源") @RequestParam(defaultValue = "excel_import") String addSource) {
        com.carhub.domain.dto.CartImportDTO dto = new com.carhub.domain.dto.CartImportDTO();
        dto.setValidateProduct(validateProduct);
        dto.setOverwrite(overwrite);
        dto.setAddSource(addSource);
        return R.ok(cartImportExportService.createImportTask(file, dto));
    }

    @ApiOperation("查询导入任务状态")
    @GetMapping("/import/{taskId}")
    public R<com.carhub.domain.vo.ImportTaskVO> getImportTaskStatus(
            @ApiParam("任务ID") @PathVariable String taskId) {
        return R.ok(cartImportExportService.getImportTaskStatus(taskId));
    }

    @ApiOperation("下载导入失败报告")
    @GetMapping("/import/{taskId}/error-report")
    public void downloadErrorReport(
            @ApiParam("任务ID") @PathVariable String taskId,
            javax.servlet.http.HttpServletResponse response) throws IOException {
        byte[] data = cartImportExportService.downloadErrorReport(taskId);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=import_error_" + taskId + ".xlsx");
        response.setContentLength(data.length);
        try (java.io.OutputStream out = response.getOutputStream()) {
            out.write(data);
            out.flush();
        }
    }

    @ApiOperation("导出购物车商品明细为Excel")
    @GetMapping("/export")
    public void exportCart(javax.servlet.http.HttpServletResponse response) throws IOException {
        byte[] data = cartImportExportService.exportCart();
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=cart_items_" + System.currentTimeMillis() + ".xlsx");
        response.setContentLength(data.length);
        try (java.io.OutputStream out = response.getOutputStream()) {
            out.write(data);
            out.flush();
        }
    }

    @Resource
    private com.carhub.storage.CartRedisStorage cartRedisStorage;

}
