package com.carhub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsOverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String startDate;

    private String endDate;

    private Long totalAddToCartCount;

    private Long totalAddToCartUserCount;

    private Long totalRemoveFromCartCount;

    private Long totalCheckoutCount;

    private Long totalPurchaseCount;

    private BigDecimal totalCartAmount;

    private BigDecimal totalPurchaseAmount;

    private BigDecimal addToCartRate;

    private BigDecimal cartAbandonmentRate;

    private BigDecimal checkoutConversionRate;

    private BigDecimal avgCartSize;

    private BigDecimal avgCartAmount;

    private BigDecimal avgCheckoutDurationSeconds;

    private List<ProductAnalyticsVO> topProducts;

    private List<Map<String, Object>> dailyTrend;

    private Map<String, Object> breakdownBySource;

    private Map<String, Object> breakdownByCategory;
}
