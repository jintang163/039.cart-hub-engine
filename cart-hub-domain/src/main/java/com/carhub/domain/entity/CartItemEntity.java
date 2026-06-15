package com.carhub.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_cart_item")
public class CartItemEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String bizType;

    private String userId;

    private Long cartId;

    private String skuId;

    private String spuId;

    private String categoryId;

    private String shopId;

    private String itemName;

    private String itemImage;

    private String itemSpec;

    private BigDecimal unitPrice;

    private BigDecimal originalPrice;

    private Integer quantity;

    private BigDecimal subtotal;

    private BigDecimal discountAmount;

    private BigDecimal payAmount;

    private Integer stock;

    private Integer onShelf;

    private Integer selected;

    private Integer priceChanged;

    private BigDecimal oldPrice;

    private LocalDateTime addTime;

    private String addSource;

    private String extInfo;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
