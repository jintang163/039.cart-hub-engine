package com.carhub.domain.dto;

import com.carhub.domain.model.CartItem;
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
public class PromotionCalculateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String userId;

    private List<CartItem> items;

    private BigDecimal totalAmount;

    private String selectedCouponId;

    private String couponCode;

    private List<String> selectedPromotionIds;

}
