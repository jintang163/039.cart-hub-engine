package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class ApplyPromotionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "促销活动ID不能为空")
    private String promotionId;

}
