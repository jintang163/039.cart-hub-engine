package com.carhub.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skuId;

    private String spuId;

    private String categoryId;

    private String shopId;

    private String itemName;

    private String itemImage;

    private Map<String, String> itemSpec;

    private BigDecimal unitPrice;

    private BigDecimal originalPrice;

    private Integer quantity;

    private BigDecimal subtotal;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private Integer stock;

    private Boolean onShelf;

    private Boolean selected;

    private Boolean priceChanged;

    private BigDecimal oldPrice;

    private Long addTime;

    private String addSource;

    private String invalidMessage;

    private Map<String, Object> extInfo;

    public void recalculate() {
        if (this.unitPrice == null) {
            this.unitPrice = BigDecimal.ZERO;
        }
        if (this.quantity == null) {
            this.quantity = 1;
        }
        if (this.discountAmount == null) {
            this.discountAmount = BigDecimal.ZERO;
        }
        this.subtotal = this.unitPrice.multiply(BigDecimal.valueOf(this.quantity));
        this.payAmount = this.subtotal.subtract(this.discountAmount);
        if (this.payAmount.compareTo(BigDecimal.ZERO) < 0) {
            this.payAmount = BigDecimal.ZERO;
        }
        if (this.selected == null) {
            this.selected = true;
        }
        if (this.onShelf == null) {
            this.onShelf = true;
        }
        if (this.priceChanged == null) {
            this.priceChanged = false;
        }
    }

}
