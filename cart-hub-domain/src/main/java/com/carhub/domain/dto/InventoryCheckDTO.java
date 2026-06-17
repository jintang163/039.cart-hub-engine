package com.carhub.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryCheckDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "待校验的商品不能为空")
    private List<InventoryItemDTO> items;

    private boolean autoDeselect;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryItemDTO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String skuId;
        private String spuId;
        private Integer quantity;
        private String itemName;
    }
}
