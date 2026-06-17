package com.carhub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryCheckVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean allAvailable;

    private boolean hasShortage;

    @Builder.Default
    private List<InventoryItemVO> items = new ArrayList<>();

    @Builder.Default
    private List<InventoryItemVO> shortageItems = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryItemVO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String skuId;
        private String spuId;
        private String itemName;
        private String itemImage;
        private Integer requestedQuantity;
        private Integer availableQuantity;
        private Integer stock;
        private boolean available;
        private String shortageReason;
        private String categoryId;
        private String categoryName;
        private String shopId;
        private java.math.BigDecimal unitPrice;
    }
}
