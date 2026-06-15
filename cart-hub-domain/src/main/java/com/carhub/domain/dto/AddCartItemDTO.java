package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class AddCartItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "SKU ID不能为空")
    private String skuId;

    private String spuId;

    private String categoryId;

    private String shopId;

    private String itemName;

    private String itemImage;

    private Map<String, String> itemSpec;

    @NotNull(message = "商品单价不能为空")
    private BigDecimal unitPrice;

    private BigDecimal originalPrice;

    @NotNull(message = "商品数量不能为空")
    private Integer quantity;

    private Integer stock;

    private Boolean selected = true;

    private String addSource;

    private String remark;

    private Integer sortWeight;

    private Map<String, Object> extInfo;

}
