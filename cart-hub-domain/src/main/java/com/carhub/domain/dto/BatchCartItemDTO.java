package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import java.io.Serializable;
import java.util.List;

@Data
public class BatchCartItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "SKU ID列表不能为空")
    private List<String> skuIds;

    private Long clientVersion;

    private Boolean forceOverwrite = false;

}
