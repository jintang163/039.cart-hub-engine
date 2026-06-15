package com.carhub.domain.model;

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
public class DiscountDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    private String discountId;

    private String discountType;

    private String discountName;

    private String discountCode;

    private BigDecimal discountAmount;

    private String promotionType;

    private String promotionName;

    private String promotionId;

    private List<String> applySkus;

    private Map<String, BigDecimal> skuDiscountAmount;

    private BigDecimal thresholdAmount;

    private BigDecimal discountValue;

    private Integer discountPercent;

    private Integer maxDiscountAmount;

    private List<GiftItem> gifts;

    private Map<String, Object> extInfo;

}
