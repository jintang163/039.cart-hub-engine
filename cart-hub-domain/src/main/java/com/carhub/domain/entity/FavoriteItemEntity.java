package com.carhub.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_favorite_item")
public class FavoriteItemEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String bizType;

    private String userId;

    private String skuId;

    private String spuId;

    private String categoryId;

    private String shopId;

    private String itemName;

    private String itemImage;

    private String itemSpec;

    private BigDecimal unitPrice;

    private BigDecimal originalPrice;

    private Integer onShelf;

    private Integer priceChanged;

    private Long addTime;

    private String addSource;

    private String extInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
