package com.carhub.domain.vo;

import com.carhub.domain.model.CartDiscount;
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
public class CartVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String userId;

    private List<CartItem> items;

    private List<CartItem> validItems;

    private List<CartItem> invalidItems;

    private Integer itemCount;

    private Integer validItemCount;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private Boolean hasPriceChanged;

    private Boolean hasInvalidItem;

    private List<CartDiscount> discounts;

    private Boolean validateEnabled;

    private Long version;

    private Long updateTime;

}
