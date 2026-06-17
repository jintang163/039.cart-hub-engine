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
public class ProductAnalyticsVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skuId;

    private String spuId;

    private String categoryId;

    private String categoryName;

    private String itemName;

    private String itemImage;

    private Long addToCartCount;

    private Long addToCartUserCount;

    private Long removeFromCartCount;

    private Long checkoutCount;

    private Long purchaseCount;

    private BigDecimal addToCartAmount;

    private BigDecimal conversionRate;

    private BigDecimal cartToPurchaseRate;

    private Long rank;
}
