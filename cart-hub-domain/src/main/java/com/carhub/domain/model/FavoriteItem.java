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
public class FavoriteItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String skuId;

    private String spuId;

    private String categoryId;

    private String shopId;

    private String itemName;

    private String itemImage;

    private Map<String, String> itemSpec;

    private BigDecimal unitPrice;

    private BigDecimal originalPrice;

    private Boolean onShelf;

    private Boolean priceChanged;

    private Long addTime;

    private String addSource;

    private Map<String, Object> extInfo;

}
