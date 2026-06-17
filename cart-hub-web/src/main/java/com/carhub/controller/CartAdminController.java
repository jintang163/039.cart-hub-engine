package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.entity.BizConfigEntity;
import com.carhub.service.BizConfigService;
import com.carhub.service.AbandonedCartCouponService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Api(tags = "管理后台-购物车统计API")
@RestController
@RequestMapping("/api/admin/cart")
@RequiredArgsConstructor
public class CartAdminController {

    private final BizConfigService bizConfigService;
    private final CartStatisticsService cartStatisticsService;
    private final CartExpireCleanupService cartExpireCleanupService;
    private final CartPriceDropNotifyService cartPriceDropNotifyService;
    private final AbandonedCartCouponService abandonedCartCouponService;

    @ApiOperation("获取购物车使用统计概览")
    @GetMapping("/statistics/overview")
    public R<Map<String, Object>> getOverview(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return R.ok(cartStatisticsService.getOverview(bizType, startDate, endDate));
    }

    @ApiOperation("按日获取统计数据")
    @GetMapping("/statistics/daily")
    public R<List<Map<String, Object>>> getDailyStatistics(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return R.ok(cartStatisticsService.getDailyStatistics(bizType, startDate, endDate));
    }

    @ApiOperation("各业务线使用情况对比")
    @GetMapping("/statistics/biz-comparison")
    public R<List<Map<String, Object>>> getBizComparison(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return R.ok(cartStatisticsService.getBizComparison(startDate, endDate));
    }

    @ApiOperation("获取业务配置列表")
    @GetMapping("/biz-config/list")
    public R<List<BizConfigEntity>> listBizConfig() {
        return R.ok(bizConfigService.listAll());
    }

    @ApiOperation("创建业务配置")
    @PostMapping("/biz-config")
    public R<Boolean> createBizConfig(@RequestBody BizConfigEntity entity) {
        return R.ok(bizConfigService.createConfig(entity));
    }

    @ApiOperation("更新业务配置")
    @PutMapping("/biz-config")
    public R<Boolean> updateBizConfig(@RequestBody BizConfigEntity entity) {
        return R.ok(bizConfigService.updateConfig(entity));
    }

    @ApiOperation("删除业务配置")
    @DeleteMapping("/biz-config/{id}")
    public R<Boolean> deleteBizConfig(@PathVariable Long id) {
        return R.ok(bizConfigService.deleteConfig(id));
    }

    @ApiOperation("实时统计：活跃用户数、商品总数（从Redis计算）")
    @GetMapping("/statistics/realtime")
    public R<Map<String, Object>> getRealtimeStatistics(
            @RequestParam(required = false) String bizType) {
        return R.ok(cartStatisticsService.getRealtimeStatistics(bizType));
    }

    @ApiOperation("手动触发过期购物车清理任务")
    @PostMapping("/cleanup/trigger")
    public R<String> triggerCleanup() {
        cartExpireCleanupService.triggerManualCleanup();
        return R.ok("清理任务已触发");
    }

    @ApiOperation("获取指定用户购物车过期信息")
    @GetMapping("/cleanup/expire-info/{userId}")
    public R<Map<String, Object>> getUserExpireInfo(
            @PathVariable String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId,
            @RequestParam(required = false, defaultValue = "ecommerce") String bizType) {
        return R.ok(cartExpireCleanupService.getExpireInfo(tenantId, bizType, userId));
    }

    @ApiOperation("手动触发降价提醒扫描任务")
    @PostMapping("/price-drop/trigger")
    public R<String> triggerPriceDropScan() {
        cartPriceDropNotifyService.triggerManualScan();
        return R.ok("降价提醒扫描任务已触发");
    }

    @ApiOperation("获取降价提醒任务统计")
    @GetMapping("/price-drop/statistics")
    public R<Map<String, Object>> getPriceDropStatistics(
            @RequestParam(required = false) String date,
            @RequestParam(required = false, defaultValue = "default") String tenantId,
            @RequestParam(required = false, defaultValue = "ecommerce") String bizType) {
        return R.ok(cartPriceDropNotifyService.getStatistics(tenantId, bizType, date));
    }

    @ApiOperation("获取指定用户的降价提醒订阅信息")
    @GetMapping("/price-drop/info/{userId}")
    public R<Map<String, Object>> getUserPriceDropInfo(
            @PathVariable String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId,
            @RequestParam(required = false, defaultValue = "ecommerce") String bizType) {
        return R.ok(cartPriceDropNotifyService.getPriceDropInfo(tenantId, bizType, userId));
    }

    @ApiOperation("手动触发放弃购物车优惠券推送任务（全租户）")
    @PostMapping("/abandoned-cart/trigger")
    public R<String> triggerAbandonedCartScan() {
        int count = abandonedCartCouponService.triggerManualScanAll();
        return R.ok("放弃购物车优惠券推送任务已触发（全租户），处理用户数: " + count);
    }

    @ApiOperation("手动触发指定租户放弃购物车优惠券推送任务")
    @PostMapping("/abandoned-cart/trigger-by-tenant")
    public R<String> triggerAbandonedCartScanByTenant(
            @RequestParam(required = false, defaultValue = "default") String tenantId,
            @RequestParam(required = false, defaultValue = "ecommerce") String bizType) {
        int count = abandonedCartCouponService.triggerManualScan(tenantId, bizType);
        return R.ok("放弃购物车优惠券推送任务已触发（tenantId=" + tenantId + ", bizType=" + bizType + "），处理用户数: " + count);
    }

    @ApiOperation("获取放弃购物车优惠券推送统计")
    @GetMapping("/abandoned-cart/statistics")
    public R<Map<String, Object>> getAbandonedCartStatistics(
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String bizType) {
        return R.ok(abandonedCartCouponService.getStatistics(tenantId, bizType, date));
    }

}
