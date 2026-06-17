package com.carhub.domain.dto;

import com.carhub.domain.model.CartItem;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.List;

@Data
public class UpdateItemRemarkDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "SKU ID不能为空")
    private String skuId;

    private String remark;

    private Long clientVersion;

    private Boolean forceOverwrite = false;

    private List<CartItem> clientItems;

}
