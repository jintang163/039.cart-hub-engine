package com.carhub.domain.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductChangeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String skuId;

    private String spuId;

    private String changeType;

    private BigDecimal oldPrice;

    private BigDecimal newPrice;

    private Integer oldStock;

    private Integer newStock;

    private Integer oldOnShelf;

    private Integer newOnShelf;

    private String itemName;

    private String itemImage;

    private LocalDateTime changeTime;

    private String operator;

    private String source;

}
