package com.carhub.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartSnapshotVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String snapshotId;

    private String snapshotName;

    private String snapshotType;

    private String snapshotTypeDesc;

    private List<CartItem> items;

    private Integer itemCount;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    private LocalDateTime createTime;

    private LocalDateTime expireTime;

    private String orderNo;
}
