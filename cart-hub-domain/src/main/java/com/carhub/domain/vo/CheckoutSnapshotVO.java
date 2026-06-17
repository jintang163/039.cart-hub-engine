package com.carhub.domain.vo;

import com.carhub.domain.model.Cart;
import com.carhub.domain.vo.InventoryCheckVO;
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
public class CheckoutSnapshotVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String checkoutToken;

    private String tenantId;

    private String bizType;

    private String userId;

    private Cart cartSnapshot;

    private Integer itemCount;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private Integer stockStatus;

    private String stockLockCode;

    private Integer status;

    private String orderNo;

    private LocalDateTime expireTime;

    private Long expireSeconds;

    private String source;

    private LocalDateTime createTime;

    private Boolean hasStockShortage;

    private List<InventoryCheckVO.InventoryItemVO> stockShortageItems;

}
