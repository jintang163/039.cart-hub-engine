package com.carhub.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_cart_share")
public class CartShareEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shareId;

    private String tenantId;

    private String bizType;

    private String ownerId;

    private String title;

    private String cartSnapshot;

    private Integer itemCount;

    private Integer totalQuantity;

    private BigDecimal totalAmount;

    private Integer shareType;

    private Integer viewCount;

    private Integer acceptCount;

    private LocalDateTime expireTime;

    private String password;

    private String extInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
