package com.carhub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutDurationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String statDate;

    private Long totalCheckouts;

    private Long completedCheckouts;

    private Long abandonedCheckouts;

    private BigDecimal avgDurationSeconds;

    private BigDecimal medianDurationSeconds;

    private BigDecimal p90DurationSeconds;

    private BigDecimal p95DurationSeconds;

    private BigDecimal avgItemCount;

    private BigDecimal avgAmount;

    private BigDecimal successRate;
}
