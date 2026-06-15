package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class UpdateCartItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "SKU ID不能为空")
    private String skuId;

    private Integer quantity;

    private BigDecimal unitPrice;

    private Boolean selected;

    private String itemName;

    private String itemImage;

    private Map<String, String> itemSpec;

    private String remark;

    private Map<String, Object> extInfo;

}
