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
public class CartDiscount implements Serializable {

    private static final long serialVersionUID = 1L;

    private String discountId;

    private String discountType;

    private String discountName;

    private String discountCode;

    private BigDecimal discountAmount;

    private Map<String, Object> discountRule;

    private String scope;

    private List<String> applySkus;

    private Boolean enable;

    private String errorMessage;

    private Map<String, Object> extInfo;

}
