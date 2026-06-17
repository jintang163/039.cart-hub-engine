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
public class CartAbandonmentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String statDate;

    private Long totalCartSessions;

    private Long checkoutStartedCount;

    private Long checkoutCompletedCount;

    private Long abandonedCount;

    private BigDecimal abandonmentRate;

    private BigDecimal checkoutConversionRate;

    private Long abandonedItemCount;

    private BigDecimal abandonedAmount;

    private BigDecimal avgAbandonedItems;

    private BigDecimal avgAbandonedAmount;
}
