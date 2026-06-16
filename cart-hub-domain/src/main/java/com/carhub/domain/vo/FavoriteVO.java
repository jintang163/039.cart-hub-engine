package com.carhub.domain.vo;

import com.carhub.domain.model.FavoriteItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantId;

    private String bizType;

    private String userId;

    private List<FavoriteItem> items;

    private Integer itemCount;

    private BigDecimal totalAmount;

    private Long updateTime;

}
