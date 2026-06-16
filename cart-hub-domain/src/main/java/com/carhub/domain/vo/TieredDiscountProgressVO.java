package com.carhub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TieredDiscountProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private BigDecimal totalAmount;

    private BigDecimal currentDiscountAmount;

    private TierInfo currentTier;

    private TierInfo nextTier;

    private List<TierInfo> allTiers;

    private String progressTip;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TierInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private String promotionId;

        private String promotionName;

        private BigDecimal thresholdAmount;

        private BigDecimal discountAmount;

        private Boolean reached;

        private BigDecimal gapAmount;

        private BigDecimal progressPercent;
    }
}
