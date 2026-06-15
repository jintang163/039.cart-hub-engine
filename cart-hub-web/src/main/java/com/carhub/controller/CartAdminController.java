package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.entity.BizConfigEntity;
import com.carhub.service.BizConfigService;
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

}
