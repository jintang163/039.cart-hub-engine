package com.carhub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String promotionId;

    private String promotionName;

    private String promotionType;

    private String promotionDesc;

    private BigDecimal thresholdAmount;

    private BigDecimal discountAmount;

    private Integer discountPercent;

    private BigDecimal maxDiscountAmount;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Boolean available;

    private String unavailableReason;

    private List<String> applySkus;

    private Boolean stackable;

    private Integer priority;

    private List<GiftItemVO> gifts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GiftItemVO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String skuId;
        private String itemName;
        private String itemImage;
        private Integer quantity;
        private BigDecimal unitPrice;
    }

}
