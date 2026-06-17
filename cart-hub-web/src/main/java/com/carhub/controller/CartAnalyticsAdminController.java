package com.carhub.controller;

import com.carhub.common.result.R;
import com.carhub.domain.dto.AnalyticsQueryDTO;
import com.carhub.domain.vo.AnalyticsOverviewVO;
import com.carhub.domain.vo.CartAbandonmentVO;
import com.carhub.domain.vo.CheckoutDurationVO;
import com.carhub.domain.vo.ProductAnalyticsVO;
import com.carhub.service.CartAnalyticsService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Api(tags = "管理后台-购物车行为分析API")
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
public class CartAnalyticsAdminController {

    private final CartAnalyticsService cartAnalyticsService;

    @ApiOperation("获取分析概览数据")
    @GetMapping("/overview")
    public R<AnalyticsOverviewVO> getOverview(
            @ApiParam("业务线") @RequestParam(required = false) String bizType,
            @ApiParam("开始日期 yyyy-MM-dd") @RequestParam(required = false) String startDate,
            @ApiParam("结束日期 yyyy-MM-dd") @RequestParam(required = false) String endDate,
            @ApiParam("Top N 商品数") @RequestParam(defaultValue = "10") Integer topN) {
        AnalyticsQueryDTO query = AnalyticsQueryDTO.builder()
                .bizType(bizType)
                .startDate(startDate)
                .endDate(endDate)
                .topN(topN)
                .build();
        return R.ok(cartAnalyticsService.getOverview(query));
    }

    @ApiOperation("获取热门商品排行榜")
    @GetMapping("/products/top")
    public R<List<ProductAnalyticsVO>> getTopProducts(
            @ApiParam("业务线") @RequestParam(required = false) String bizType,
            @ApiParam("开始日期") @RequestParam(required = false) String startDate,
            @ApiParam("结束日期") @RequestParam(required = false) String endDate,
            @ApiParam("排名数量") @RequestParam(defaultValue = "20") Integer topN,
            @ApiParam("排序字段: add_count/purchase_count/remove_count/add_amount")
            @RequestParam(defaultValue = "add_count") String sortBy) {
        String tenantId = com.carhub.common.context.CartContextHolder.getTenantId();
        return R.ok(cartAnalyticsService.getTopProducts(tenantId, bizType, startDate, endDate, topN, sortBy));
    }

    @ApiOperation("获取购物车放弃率趋势")
    @GetMapping("/abandonment/trend")
    public R<List<CartAbandonmentVO>> getAbandonmentTrend(
            @ApiParam("业务线") @RequestParam(required = false) String bizType,
            @ApiParam("开始日期") @RequestParam(required = false) String startDate,
            @ApiParam("结束日期") @RequestParam(required = false) String endDate) {
        AnalyticsQueryDTO query = AnalyticsQueryDTO.builder()
                .bizType(bizType)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        return R.ok(cartAnalyticsService.getCartAbandonmentTrend(query));
    }

    @ApiOperation("获取结算时长趋势")
    @GetMapping("/checkout/duration")
    public R<List<CheckoutDurationVO>> getCheckoutDurationTrend(
            @ApiParam("业务线") @RequestParam(required = false) String bizType,
            @ApiParam("开始日期") @RequestParam(required = false) String startDate,
            @ApiParam("结束日期") @RequestParam(required = false) String endDate) {
        AnalyticsQueryDTO query = AnalyticsQueryDTO.builder()
                .bizType(bizType)
                .startDate(startDate)
                .endDate(endDate)
                .build();
        return R.ok(cartAnalyticsService.getCheckoutDurationTrend(query));
    }

    @ApiOperation("多维度下钻分析")
    @GetMapping("/drilldown")
    public R<Map<String, Object>> drillDown(
            @ApiParam("维度: biz_type/category/source/product/hour")
            @RequestParam(defaultValue = "biz_type") String dimension,
            @ApiParam("业务线") @RequestParam(required = false) String bizType,
            @ApiParam("商品分类ID") @RequestParam(required = false) String categoryId,
            @ApiParam("来源") @RequestParam(required = false) String source,
            @ApiParam("开始日期") @RequestParam(required = false) String startDate,
            @ApiParam("结束日期") @RequestParam(required = false) String endDate,
            @ApiParam("返回数量") @RequestParam(defaultValue = "20") Integer topN) {
        AnalyticsQueryDTO query = AnalyticsQueryDTO.builder()
                .dimension(dimension)
                .bizType(bizType)
                .categoryId(categoryId)
                .source(source)
                .startDate(startDate)
                .endDate(endDate)
                .topN(topN)
                .build();
        return R.ok(cartAnalyticsService.drillDown(query));
    }
}
