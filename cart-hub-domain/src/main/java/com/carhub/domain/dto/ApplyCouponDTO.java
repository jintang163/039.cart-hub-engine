package com.carhub.domain.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
public class ApplyCouponDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "优惠券码不能为空")
    private String couponCode;

}
