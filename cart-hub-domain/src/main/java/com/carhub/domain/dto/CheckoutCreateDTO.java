package com.carhub.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutCreateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<String> skuIds;

    private String addressId;

    private String couponId;

    private String remark;

    private String source;

}
