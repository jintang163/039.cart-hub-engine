package com.carhub.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cart implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String userId;

    private List<CartItem> items;

    private Integer itemCount;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private Boolean hasPriceChanged;

    private Boolean hasInvalidItem;

    private List<CartDiscount> discounts;

    private Long version;

    private Long updateTime;

    private Map<String, Object> extInfo;

    public void recalculate() {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.forEach(CartItem::recalculate);
        this.itemCount = this.items.size();
        this.totalQuantity = this.items.stream()
                .filter(i -> Boolean.TRUE.equals(i.getSelected()))
                .mapToInt(CartItem::getQuantity)
                .sum();
        this.totalAmount = this.items.stream()
                .filter(i -> Boolean.TRUE.equals(i.getSelected()))
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (this.discounts == null) {
            this.discounts = new ArrayList<>();
        }
        this.discountAmount = this.discounts.stream()
                .map(CartDiscount::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.payAmount = this.totalAmount.subtract(this.discountAmount);
        if (this.payAmount.compareTo(BigDecimal.ZERO) < 0) {
            this.payAmount = BigDecimal.ZERO;
        }
        this.hasPriceChanged = this.items.stream()
                .anyMatch(i -> Boolean.TRUE.equals(i.getPriceChanged()));
        this.hasInvalidItem = this.items.stream()
                .anyMatch(i -> Boolean.FALSE.equals(i.getOnShelf()) ||
                        (i.getInvalidMessage() != null && !i.getInvalidMessage().isEmpty()));
        this.updateTime = System.currentTimeMillis();
    }

    public List<CartItem> getItemsSortedByAddTime() {
        if (this.items == null) {
            return new ArrayList<>();
        }
        return this.items.stream()
                .sorted(Comparator.comparing(CartItem::getAddTime, Comparator.nullsLast(Long::compareTo)).reversed())
                .collect(Collectors.toList());
    }

    public CartItem getItemBySku(String skuId) {
        if (this.items == null || skuId == null) {
            return null;
        }
        return this.items.stream()
                .filter(i -> skuId.equals(i.getSkuId()))
                .findFirst()
                .orElse(null);
    }

    public boolean addItem(CartItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        if (item == null || item.getSkuId() == null) {
            return false;
        }
        CartItem exist = getItemBySku(item.getSkuId());
        if (exist != null) {
            return false;
        }
        item.recalculate();
        this.items.add(item);
        return true;
    }

    public boolean removeItem(String skuId) {
        if (this.items == null || skuId == null) {
            return false;
        }
        return this.items.removeIf(i -> skuId.equals(i.getSkuId()));
    }

}
