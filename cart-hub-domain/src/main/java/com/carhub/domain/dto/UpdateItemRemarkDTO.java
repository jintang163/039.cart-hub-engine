package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class UpdateItemRemarkDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "SKU ID不能为空")
    private String skuId;

    private String remark;

    private Long clientVersion;

    private Boolean forceOverwrite = false;

}
