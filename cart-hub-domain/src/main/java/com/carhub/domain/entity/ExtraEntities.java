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

@Data
@TableName("t_cart_snapshot")
class CartSnapshotEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String snapshotId;
    private String tenantId;
    private String bizType;
    private String userId;
    private String snapshotName;
    private String snapshotType;
    private String cartSnapshot;
    private Integer itemCount;
    private Integer totalQuantity;
    private BigDecimal totalAmount;
    private String orderNo;
    private Integer storageType;
    private String storageUrl;
    private LocalDateTime expireTime;
    private String extInfo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}

@Data
@TableName("t_cart_discount")
class CartDiscountEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String bizType;
    private String userId;
    private Long cartId;
    private String discountId;
    private String discountType;
    private String discountName;
    private String discountCode;
    private BigDecimal discountAmount;
    private String discountRule;
    private String scope;
    private String applyItems;
    private Integer status;
    private String extInfo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}

@Data
@TableName("t_cart_history")
class CartHistoryEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String bizType;
    private String userId;
    private String action;
    private String skuId;
    private Integer oldQuantity;
    private Integer newQuantity;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private String operator;
    private String operatorType;
    private String source;
    private String clientIp;
    private String remark;
    private String detail;
    private String traceId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

@Data
@TableName("t_cart_statistics")
class CartStatisticsEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String bizType;
    private java.time.LocalDate statDate;
    private Integer activeUserCount;
    private Integer newUserCount;
    private Integer addUserCount;
    private Integer totalItemCount;
    private Integer totalAddCount;
    private Integer totalDeleteCount;
    private Integer totalUpdateCount;
    private Integer totalClearCount;
    private BigDecimal avgCartSize;
    private BigDecimal avgCartAmount;
    private Integer shareCount;
    private Integer snapshotCount;
    private String extInfo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

@Data
@TableName("t_tenant")
class TenantEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String tenantName;
    private Integer status;
    private String contactName;
    private String contactPhone;
    private LocalDateTime expireTime;
    private String description;
    private String extInfo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}

@Data
@TableName("t_coupon_template")
public class CouponTemplateEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String bizType;
    private String couponId;
    private String couponName;
    private String couponType;
    private String promotionType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private Integer discountPercent;
    private BigDecimal maxDiscountAmount;
    private String scope;
    private String applyCategoryIds;
    private String applyShopIds;
    private String applySkuIds;
    private String excludeSkuIds;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer totalCount;
    private Integer usedCount;
    private Integer perUserLimit;
    private Boolean stackable;
    private Integer priority;
    private String couponDesc;
    private String giftInfo;
    private String ruleConfig;
    private Integer status;
    private String extInfo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}

@Data
@TableName("t_promotion_activity")
class PromotionActivityEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String bizType;
    private String promotionId;
    private String promotionName;
    private String promotionType;
    private String promotionDesc;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private Integer discountPercent;
    private BigDecimal maxDiscountAmount;
    private String scope;
    private String applyCategoryIds;
    private String applyShopIds;
    private String applySkuIds;
    private String excludeSkuIds;
    private String giftInfo;
    private String ruleConfig;
    private Boolean stackable;
    private Integer priority;
    private Integer status;
    private String extInfo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}

@Data
@TableName("t_user_coupon")
class UserCouponEntity implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String bizType;
    private String userId;
    private String couponId;
    private String couponCode;
    private String couponName;
    private String couponType;
    private String promotionType;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private Integer discountPercent;
    private BigDecimal maxDiscountAmount;
    private LocalDateTime receiveTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime usedTime;
    private String orderNo;
    private Integer status;
    private String extInfo;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
