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
public class CouponVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String couponId;

    private String couponName;

    private String couponType;

    private String promotionType;

    private BigDecimal thresholdAmount;

    private BigDecimal discountAmount;

    private Integer discountPercent;

    private BigDecimal maxDiscountAmount;

    private String couponDesc;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Boolean available;

    private String unavailableReason;

    private List<String> applySkus;

    private Boolean stackable;

    private Integer priority;

}
