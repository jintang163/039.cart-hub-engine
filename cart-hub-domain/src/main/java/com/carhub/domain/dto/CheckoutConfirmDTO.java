package com.carhub.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutConfirmDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "结算Token不能为空")
    private String checkoutToken;

    private String orderNo;

    private String remark;

}
