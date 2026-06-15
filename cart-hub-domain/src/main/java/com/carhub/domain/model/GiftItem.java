package com.carhub.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GiftItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skuId;

    private String spuId;

    private String itemName;

    private String itemImage;

    private Map<String, String> itemSpec;

    private Integer quantity;

    private BigDecimal unitPrice;

    private Map<String, Object> extInfo;

}
