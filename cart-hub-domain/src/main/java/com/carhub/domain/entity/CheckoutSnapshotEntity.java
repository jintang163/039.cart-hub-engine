package com.carhub.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_checkout_snapshot")
public class CheckoutSnapshotEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String checkoutToken;

    private String tenantId;

    private String bizType;

    private String userId;

    private String cartSnapshot;

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

    private String clientIp;

    private String source;

    private String extInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
