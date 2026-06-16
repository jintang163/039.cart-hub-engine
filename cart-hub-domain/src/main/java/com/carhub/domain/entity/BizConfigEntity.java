package com.carhub.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("t_biz_config")
public class BizConfigEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private String bizType;

    private String bizName;

    private Integer status;

    private String validateUrl;

    private Integer validateTimeout;

    private Integer validateCacheSec;

    private Integer maxCartSize;

    private Integer maxItemQuantity;

    private Integer cartExpireSec;

    private Integer shareExpireSec;

    private Integer snapshotExpireSec;

    private Integer discountEnable;

    private Integer itemRetentionDays;

    private Integer remindBeforeDays;

    private Integer cleanupEnable;

    private Integer cleanupArchiveToDb;

    private Integer cleanupArchiveRetentionDays;

    private Integer cleanupNotifyEnable;

    private String cleanupNotifyChannels;

    private String cleanupWechatTemplateId;

    private String cleanupSmsTemplateId;

    private String cleanupNotifyApiUrl;

    private String description;

    private String extConfig;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
