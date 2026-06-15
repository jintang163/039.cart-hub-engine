package com.carhub.domain.vo;

import com.carhub.domain.model.CartDiscount;
import com.carhub.domain.model.DiscountDetail;
import com.carhub.domain.model.GiftItem;
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
public class DiscountResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private List<CartDiscount> discounts;

    private List<DiscountDetail> discountDetails;

    private List<GiftItem> gifts;

    private String errorMessage;

    private Boolean success;

}
