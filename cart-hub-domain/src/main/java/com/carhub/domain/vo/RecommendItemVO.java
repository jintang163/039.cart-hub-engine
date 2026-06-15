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
public class RecommendItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skuId;

    private String spuId;

    private String itemName;

    private String itemImage;

    private Map<String, String> itemSpec;

    private BigDecimal unitPrice;

    private BigDecimal originalPrice;

    private Double score;

    private String recommendReason;

    private Integer coOccurrenceCount;

    private Double support;

    private Double confidence;

    private Double lift;

    private List<String> sourceSkus;

    private Map<String, Object> extInfo;

}
