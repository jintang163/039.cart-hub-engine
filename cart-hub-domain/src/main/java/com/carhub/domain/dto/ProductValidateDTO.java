package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class ProductValidateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "SKU ID不能为空")
    private String skuId;

    private String spuId;

    private BigDecimal unitPrice;

    private Integer quantity;

}
