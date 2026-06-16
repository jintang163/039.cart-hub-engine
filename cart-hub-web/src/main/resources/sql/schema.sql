-- ============================================================
-- 购物车引擎 Cart-Hub-Engine 数据库初始化脚本
-- 数据库: MySQL 8.0+
-- ============================================================

CREATE DATABASE IF NOT EXISTS `cart_hub`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `cart_hub`;

-- ============================================================
-- 1. 租户信息表
-- ============================================================
DROP TABLE IF EXISTS `t_tenant`;
CREATE TABLE `t_tenant` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`       VARCHAR(64)  NOT NULL COMMENT '租户ID(业务唯一标识)',
    `tenant_name`     VARCHAR(128) NOT NULL COMMENT '租户名称',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-禁用,1-启用',
    `contact_name`    VARCHAR(64)  DEFAULT NULL COMMENT '联系人',
    `contact_phone`   VARCHAR(32)  DEFAULT NULL COMMENT '联系电话',
    `expire_time`     DATETIME     DEFAULT NULL COMMENT '过期时间',
    `description`     VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `ext_info`        JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_id` (`tenant_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户信息表';

-- ============================================================
-- 2. 业务类型配置表
-- ============================================================
DROP TABLE IF EXISTS `t_biz_config`;
CREATE TABLE `t_biz_config` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`          VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`           VARCHAR(64)  NOT NULL COMMENT '业务类型: ecommerce/food/course等',
    `biz_name`           VARCHAR(128) NOT NULL COMMENT '业务名称',
    `status`             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-禁用,1-启用',
    `validate_url`       VARCHAR(256) DEFAULT NULL COMMENT '商品校验接口URL',
    `validate_timeout`   INT          NOT NULL DEFAULT 3000 COMMENT '校验超时时间(ms)',
    `validate_cache_sec` INT          NOT NULL DEFAULT 300 COMMENT '校验结果缓存时间(s)',
    `max_cart_size`      INT          NOT NULL DEFAULT 200 COMMENT '购物车最大商品数',
    `max_item_quantity`  INT          NOT NULL DEFAULT 999 COMMENT '单商品最大数量',
    `cart_expire_sec`    INT          NOT NULL DEFAULT 864000 COMMENT '购物车过期时间(s)',
    `share_expire_sec`   INT          NOT NULL DEFAULT 86400 COMMENT '分享过期时间(s)',
    `snapshot_expire_sec`INT          NOT NULL DEFAULT 2592000 COMMENT '快照过期时间(s)',
    `discount_enable`    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用优惠:0-否,1-是',
    `description`        VARCHAR(512) DEFAULT NULL COMMENT '描述',
    `ext_config`         JSON         DEFAULT NULL COMMENT '扩展配置',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_biz` (`tenant_id`, `biz_type`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务类型配置表';

-- ============================================================
-- 3. 购物车表(主存储,Redis主,MySQL为备份)
-- ============================================================
DROP TABLE IF EXISTS `t_cart`;
CREATE TABLE `t_cart` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`       VARCHAR(128) NOT NULL COMMENT '用户ID',
    `item_count`    INT          NOT NULL DEFAULT 0 COMMENT '购物车商品项数',
    `total_quantity`INT          NOT NULL DEFAULT 0 COMMENT '商品总数量',
    `total_amount`  DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '商品总金额(原价)',
    `discount_amount`DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '优惠总金额',
    `pay_amount`    DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '实付总金额',
    `last_sync_time`DATETIME     DEFAULT NULL COMMENT '最后同步时间',
    `version`       BIGINT       NOT NULL DEFAULT 0 COMMENT '版本号(乐观锁)',
    `ext_info`      JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_biz_user` (`tenant_id`, `biz_type`, `user_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- ============================================================
-- 4. 购物车商品项表
-- ============================================================
DROP TABLE IF EXISTS `t_cart_item`;
CREATE TABLE `t_cart_item` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`       VARCHAR(128) NOT NULL COMMENT '用户ID',
    `cart_id`       BIGINT       NOT NULL COMMENT '购物车ID',
    `sku_id`        VARCHAR(128) NOT NULL COMMENT '商品SKU ID',
    `spu_id`        VARCHAR(128) DEFAULT NULL COMMENT '商品SPU ID',
    `category_id`   VARCHAR(64)  DEFAULT NULL COMMENT '分类ID',
    `shop_id`       VARCHAR(64)  DEFAULT NULL COMMENT '店铺ID',
    `item_name`     VARCHAR(256) NOT NULL COMMENT '商品名称',
    `item_image`    VARCHAR(512) DEFAULT NULL COMMENT '商品图片',
    `item_spec`     VARCHAR(512) DEFAULT NULL COMMENT '商品规格(JSON)',
    `unit_price`    DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '商品单价(快照)',
    `original_price`DECIMAL(18,2)DEFAULT 0.00 COMMENT '原价(划线价)',
    `quantity`      INT          NOT NULL DEFAULT 1 COMMENT '购买数量',
    `subtotal`      DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '小计金额',
    `discount_amount`DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '优惠金额',
    `pay_amount`    DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '实付金额',
    `stock`         INT          DEFAULT NULL COMMENT '库存(校验时更新)',
    `on_shelf`      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否上架:0-下架,1-上架',
    `selected`      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否选中:0-未选中,1-选中',
    `price_changed` TINYINT      NOT NULL DEFAULT 0 COMMENT '价格是否变动:0-否,1-是',
    `old_price`     DECIMAL(18,2)DEFAULT NULL COMMENT '变动前价格',
    `add_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入购物车时间',
    `add_source`    VARCHAR(32)  DEFAULT NULL COMMENT '加入来源:web/app/mini',
    `ext_info`      JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_biz_user_sku` (`tenant_id`, `biz_type`, `user_id`, `sku_id`),
    KEY `idx_cart_id` (`cart_id`),
    KEY `idx_sku_id` (`sku_id`),
    KEY `idx_update_time` (`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车商品项表';

-- ============================================================
-- 5. 购物车操作历史表
-- ============================================================
DROP TABLE IF EXISTS `t_cart_history`;
CREATE TABLE `t_cart_history` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`       VARCHAR(128) NOT NULL COMMENT '用户ID',
    `action`        VARCHAR(32)  NOT NULL COMMENT '操作类型:add/update/delete/clear/merge',
    `sku_id`        VARCHAR(128) DEFAULT NULL COMMENT '商品SKU ID',
    `old_quantity`  INT          DEFAULT NULL COMMENT '变更前数量',
    `new_quantity`  INT          DEFAULT NULL COMMENT '变更后数量',
    `old_price`     DECIMAL(18,2)DEFAULT NULL COMMENT '变更前单价',
    `new_price`     DECIMAL(18,2)DEFAULT NULL COMMENT '变更后单价',
    `operator`      VARCHAR(128) DEFAULT NULL COMMENT '操作人',
    `operator_type` VARCHAR(32)  DEFAULT NULL COMMENT '操作人类型:user/system',
    `source`        VARCHAR(32)  DEFAULT NULL COMMENT '操作来源:web/app/mini',
    `client_ip`     VARCHAR(64)  DEFAULT NULL COMMENT '客户端IP',
    `remark`        VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `detail`        JSON         DEFAULT NULL COMMENT '操作详情',
    `trace_id`      VARCHAR(128) DEFAULT NULL COMMENT '链路追踪ID',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user` (`tenant_id`, `biz_type`, `user_id`),
    KEY `idx_action` (`action`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车操作历史表';

-- ============================================================
-- 6. 购物车分享表
-- ============================================================
DROP TABLE IF EXISTS `t_cart_share`;
CREATE TABLE `t_cart_share` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `share_id`      VARCHAR(64)  NOT NULL COMMENT '分享ID',
    `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `owner_id`      VARCHAR(128) NOT NULL COMMENT '分享者用户ID',
    `title`         VARCHAR(256) DEFAULT NULL COMMENT '分享标题',
    `cart_snapshot` JSON         NOT NULL COMMENT '购物车快照数据',
    `item_count`    INT          NOT NULL DEFAULT 0 COMMENT '商品项数',
    `total_quantity`INT          NOT NULL DEFAULT 0 COMMENT '商品总数量',
    `total_amount`  DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '总金额',
    `share_type`    TINYINT      NOT NULL DEFAULT 1 COMMENT '分享类型:1-只读,2-可编辑',
    `view_count`    INT          NOT NULL DEFAULT 0 COMMENT '查看次数',
    `accept_count`  INT          NOT NULL DEFAULT 0 COMMENT '接受次数',
    `expire_time`   DATETIME     NOT NULL COMMENT '过期时间',
    `password`      VARCHAR(64)  DEFAULT NULL COMMENT '访问密码(可选)',
    `ext_info`      JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_share_id` (`share_id`),
    KEY `idx_owner` (`tenant_id`, `biz_type`, `owner_id`),
    KEY `idx_expire` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车分享表';

-- ============================================================
-- 7. 购物车快照表
-- ============================================================
DROP TABLE IF EXISTS `t_cart_snapshot`;
CREATE TABLE `t_cart_snapshot` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `snapshot_id`   VARCHAR(64)  NOT NULL COMMENT '快照ID',
    `tenant_id`     VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`      VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`       VARCHAR(128) NOT NULL COMMENT '用户ID',
    `snapshot_name` VARCHAR(256) DEFAULT NULL COMMENT '快照名称',
    `snapshot_type` VARCHAR(32)  NOT NULL COMMENT '快照类型:manual/auto/share/order',
    `cart_snapshot` JSON         NOT NULL COMMENT '购物车快照数据',
    `item_count`    INT          NOT NULL DEFAULT 0 COMMENT '商品项数',
    `total_quantity`INT          NOT NULL DEFAULT 0 COMMENT '商品总数量',
    `total_amount`  DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '总金额',
    `order_no`      VARCHAR(128) DEFAULT NULL COMMENT '关联订单号',
    `storage_type`  TINYINT      NOT NULL DEFAULT 1 COMMENT '存储位置:1-MySQL,2-MinIO',
    `storage_url`   VARCHAR(512) DEFAULT NULL COMMENT 'MinIO存储URL',
    `expire_time`   DATETIME     DEFAULT NULL COMMENT '过期时间',
    `ext_info`      JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
    KEY `idx_user` (`tenant_id`, `biz_type`, `user_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车快照表';

-- ============================================================
-- 8. 购物车优惠记录表
-- ============================================================
DROP TABLE IF EXISTS `t_cart_discount`;
CREATE TABLE `t_cart_discount` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`      VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`       VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`        VARCHAR(128) NOT NULL COMMENT '用户ID',
    `cart_id`        BIGINT       NOT NULL COMMENT '购物车ID',
    `discount_id`    VARCHAR(128) NOT NULL COMMENT '优惠ID',
    `discount_type`  VARCHAR(32)  NOT NULL COMMENT '优惠类型:coupon/promotion/vip/member',
    `discount_name`  VARCHAR(256) NOT NULL COMMENT '优惠名称',
    `discount_code`  VARCHAR(64)  DEFAULT NULL COMMENT '优惠码',
    `discount_amount`DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '优惠金额',
    `discount_rule`  JSON         DEFAULT NULL COMMENT '优惠规则',
    `scope`          VARCHAR(32)  NOT NULL DEFAULT 'all' COMMENT '适用范围:all/item',
    `apply_items`    JSON         DEFAULT NULL COMMENT '适用的SKU列表',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-未生效,1-生效',
    `ext_info`       JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cart_discount` (`cart_id`, `discount_id`),
    KEY `idx_user` (`tenant_id`, `biz_type`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车优惠记录表';

-- ============================================================
-- 9. 购物车统计表(按天聚合)
-- ============================================================
DROP TABLE IF EXISTS `t_cart_statistics`;
CREATE TABLE `t_cart_statistics` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`         VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`          VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `stat_date`         DATE         NOT NULL COMMENT '统计日期',
    `active_user_count` INT          NOT NULL DEFAULT 0 COMMENT '活跃用户数',
    `new_user_count`    INT          NOT NULL DEFAULT 0 COMMENT '新增用户数',
    `add_user_count`    INT          NOT NULL DEFAULT 0 COMMENT '加购用户数',
    `total_item_count`  INT          NOT NULL DEFAULT 0 COMMENT '商品总数',
    `total_add_count`   INT          NOT NULL DEFAULT 0 COMMENT '加购次数',
    `total_delete_count`INT          NOT NULL DEFAULT 0 COMMENT '删除次数',
    `total_update_count`INT          NOT NULL DEFAULT 0 COMMENT '修改次数',
    `total_clear_count` INT          NOT NULL DEFAULT 0 COMMENT '清空次数',
    `avg_cart_size`     DECIMAL(10,2)NOT NULL DEFAULT 0.00 COMMENT '平均购物车大小',
    `avg_cart_amount`   DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '平均购物车金额',
    `share_count`       INT          NOT NULL DEFAULT 0 COMMENT '分享次数',
    `snapshot_count`    INT          NOT NULL DEFAULT 0 COMMENT '快照次数',
    `ext_info`          JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_biz_date` (`tenant_id`, `biz_type`, `stat_date`),
    KEY `idx_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车统计表';

-- ============================================================
-- 10. 优惠券模板表
-- ============================================================
DROP TABLE IF EXISTS `t_coupon_template`;
CREATE TABLE `t_coupon_template` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`             VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`              VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `coupon_id`             VARCHAR(128) NOT NULL COMMENT '优惠券ID',
    `coupon_name`           VARCHAR(256) NOT NULL COMMENT '优惠券名称',
    `coupon_type`           VARCHAR(32)  NOT NULL COMMENT '优惠券类型:fixed-满减,percent-折扣',
    `promotion_type`        VARCHAR(32)  DEFAULT NULL COMMENT '促销类型:full_reduction-满减,discount-折扣,gift-赠品',
    `threshold_amount`      DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '使用门槛金额',
    `discount_amount`       DECIMAL(18,2)DEFAULT 0.00 COMMENT '优惠金额(满减用)',
    `discount_percent`      INT          DEFAULT NULL COMMENT '折扣百分比(1-100,折扣用)',
    `max_discount_amount`   DECIMAL(18,2)DEFAULT NULL COMMENT '最大优惠金额',
    `scope`                 VARCHAR(32)  NOT NULL DEFAULT 'all' COMMENT '适用范围:all-全部,category-分类,shop-店铺,sku-指定商品',
    `apply_category_ids`    VARCHAR(1024)DEFAULT NULL COMMENT '适用分类ID列表(逗号分隔)',
    `apply_shop_ids`        VARCHAR(1024)DEFAULT NULL COMMENT '适用店铺ID列表(逗号分隔)',
    `apply_sku_ids`         TEXT         DEFAULT NULL COMMENT '适用SKU ID列表(逗号分隔)',
    `exclude_sku_ids`       TEXT         DEFAULT NULL COMMENT '排除SKU ID列表(逗号分隔)',
    `start_time`            DATETIME     NOT NULL COMMENT '生效开始时间',
    `end_time`              DATETIME     NOT NULL COMMENT '生效结束时间',
    `total_count`           INT          NOT NULL DEFAULT 0 COMMENT '发放总数量',
    `used_count`            INT          NOT NULL DEFAULT 0 COMMENT '已使用数量',
    `per_user_limit`        INT          NOT NULL DEFAULT 1 COMMENT '每人限领数量',
    `stackable`             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否可叠加:0-否,1-是',
    `priority`              INT          NOT NULL DEFAULT 0 COMMENT '优先级(数字越小优先级越高)',
    `coupon_desc`           VARCHAR(512) DEFAULT NULL COMMENT '优惠券描述',
    `gift_info`             JSON         DEFAULT NULL COMMENT '赠品信息(JSON数组)',
    `rule_config`           JSON         DEFAULT NULL COMMENT '扩展规则配置',
    `status`                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-禁用,1-启用',
    `ext_info`              JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coupon_id` (`tenant_id`, `biz_type`, `coupon_id`),
    KEY `idx_status` (`status`),
    KEY `idx_time` (`start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券模板表';

-- ============================================================
-- 11. 促销活动表
-- ============================================================
DROP TABLE IF EXISTS `t_promotion_activity`;
CREATE TABLE `t_promotion_activity` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`             VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`              VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `promotion_id`          VARCHAR(128) NOT NULL COMMENT '促销活动ID',
    `promotion_name`        VARCHAR(256) NOT NULL COMMENT '促销活动名称',
    `promotion_type`        VARCHAR(32)  NOT NULL COMMENT '促销类型:full_reduction-满减,discount-折扣,gift-赠品',
    `promotion_desc`        VARCHAR(512) DEFAULT NULL COMMENT '促销活动描述',
    `start_time`            DATETIME     NOT NULL COMMENT '活动开始时间',
    `end_time`              DATETIME     NOT NULL COMMENT '活动结束时间',
    `threshold_amount`      DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '门槛金额',
    `discount_amount`       DECIMAL(18,2)DEFAULT 0.00 COMMENT '优惠金额(满减用)',
    `discount_percent`      INT          DEFAULT NULL COMMENT '折扣百分比(1-100,折扣用)',
    `max_discount_amount`   DECIMAL(18,2)DEFAULT NULL COMMENT '最大优惠金额',
    `scope`                 VARCHAR(32)  NOT NULL DEFAULT 'all' COMMENT '适用范围:all-全部,category-分类,shop-店铺,sku-指定商品',
    `apply_category_ids`    VARCHAR(1024)DEFAULT NULL COMMENT '适用分类ID列表(逗号分隔)',
    `apply_shop_ids`        VARCHAR(1024)DEFAULT NULL COMMENT '适用店铺ID列表(逗号分隔)',
    `apply_sku_ids`         TEXT         DEFAULT NULL COMMENT '适用SKU ID列表(逗号分隔)',
    `exclude_sku_ids`       TEXT         DEFAULT NULL COMMENT '排除SKU ID列表(逗号分隔)',
    `gift_info`             JSON         DEFAULT NULL COMMENT '赠品信息(JSON数组)',
    `rule_config`           JSON         DEFAULT NULL COMMENT '扩展规则配置',
    `stackable`             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否可叠加:0-否,1-是',
    `priority`              INT          NOT NULL DEFAULT 0 COMMENT '优先级(数字越小优先级越高)',
    `status`                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-禁用,1-启用',
    `ext_info`              JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_promotion_id` (`tenant_id`, `biz_type`, `promotion_id`),
    KEY `idx_status` (`status`),
    KEY `idx_time` (`start_time`, `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销活动表';

-- ============================================================
-- 12. 用户优惠券表
-- ============================================================
DROP TABLE IF EXISTS `t_user_coupon`;
CREATE TABLE `t_user_coupon` (
    `id`                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`             VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`              VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`               VARCHAR(128) NOT NULL COMMENT '用户ID',
    `coupon_id`             VARCHAR(128) NOT NULL COMMENT '优惠券模板ID',
    `coupon_code`           VARCHAR(64)  DEFAULT NULL COMMENT '优惠券码',
    `coupon_name`           VARCHAR(256) NOT NULL COMMENT '优惠券名称(快照)',
    `coupon_type`           VARCHAR(32)  NOT NULL COMMENT '优惠券类型:fixed-满减,percent-折扣',
    `promotion_type`        VARCHAR(32)  DEFAULT NULL COMMENT '促销类型',
    `threshold_amount`      DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '使用门槛金额',
    `discount_amount`       DECIMAL(18,2)DEFAULT 0.00 COMMENT '优惠金额',
    `discount_percent`      INT          DEFAULT NULL COMMENT '折扣百分比',
    `max_discount_amount`   DECIMAL(18,2)DEFAULT NULL COMMENT '最大优惠金额',
    `receive_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    `start_time`            DATETIME     NOT NULL COMMENT '生效开始时间',
    `end_time`              DATETIME     NOT NULL COMMENT '生效结束时间',
    `used_time`             DATETIME     DEFAULT NULL COMMENT '使用时间',
    `order_no`              VARCHAR(128) DEFAULT NULL COMMENT '关联订单号',
    `status`                TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-已使用,1-未使用,2-已过期',
    `ext_info`              JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_coupon_code` (`tenant_id`, `biz_type`, `coupon_code`),
    KEY `idx_user` (`tenant_id`, `biz_type`, `user_id`),
    KEY `idx_status` (`status`),
    KEY `idx_coupon_id` (`coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户优惠券表';

-- ============================================================
-- 初始化数据
-- ============================================================

-- 默认租户
INSERT INTO `t_tenant` (`tenant_id`, `tenant_name`, `status`, `description`)
VALUES ('default', '默认租户', 1, '系统默认租户');

-- 业务配置
INSERT INTO `t_biz_config` (`tenant_id`, `biz_type`, `biz_name`, `status`,
    `max_cart_size`, `max_item_quantity`, `description`)
VALUES
    ('default', 'ecommerce', '电商业务', 1, 200, 999, '电商购物车业务配置'),
    ('default', 'food',      '餐饮业务', 1, 100, 99,  '餐饮购物车业务配置'),
    ('default', 'course',    '课程业务', 1, 50,  10,  '课程购物车业务配置');

-- 优惠券模板示例
INSERT INTO `t_coupon_template` (`tenant_id`, `biz_type`, `coupon_id`, `coupon_name`,
    `coupon_type`, `promotion_type`, `threshold_amount`, `discount_amount`,
    `start_time`, `end_time`, `total_count`, `per_user_limit`, `coupon_desc`, `status`)
VALUES
    ('default', 'ecommerce', 'COUPON001', '满100减10优惠券', 'fixed', 'full_reduction', 100.00, 10.00,
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 1000, 1, '全场满100元减10元', 1),
    ('default', 'ecommerce', 'COUPON002', '9折优惠券', 'percent', 'discount', 50.00, NULL,
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 500, 1, '全场满50元享9折，最高减50元', 1);

-- 促销活动示例(阶梯式满减)
INSERT INTO `t_promotion_activity` (`tenant_id`, `biz_type`, `promotion_id`, `promotion_name`,
    `promotion_type`, `promotion_desc`, `start_time`, `end_time`, `threshold_amount`,
    `discount_amount`, `priority`, `status`)
VALUES
    ('default', 'ecommerce', 'PROMO001', '满99减10', 'full_reduction', '全场满99元减10元',
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 99.00, 10.00, 1, 1),
    ('default', 'ecommerce', 'PROMO002', '满199减25', 'full_reduction', '全场满199元减25元',
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 199.00, 25.00, 2, 1),
    ('default', 'ecommerce', 'PROMO003', '满299减45', 'full_reduction', '全场满299元减45元',
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 299.00, 45.00, 3, 1),
    ('default', 'ecommerce', 'PROMO004', '满499减80', 'full_reduction', '全场满499元减80元',
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 499.00, 80.00, 4, 1),
    ('default', 'ecommerce', 'PROMO005', '满799减150', 'full_reduction', '全场满799元减150元',
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 799.00, 150.00, 5, 1),
    ('default', 'ecommerce', 'PROMO006', '满1299减280', 'full_reduction', '全场满1299元减280元',
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 1299.00, 280.00, 6, 1);

-- 用户优惠券示例
INSERT INTO `t_user_coupon` (`tenant_id`, `biz_type`, `user_id`, `coupon_id`, `coupon_code`,
    `coupon_name`, `coupon_type`, `promotion_type`, `threshold_amount`, `discount_amount`,
    `start_time`, `end_time`, `status`)
VALUES
    ('default', 'ecommerce', 'user001', 'COUPON001', 'ABC123456', '满100减10优惠券', 'fixed', 'full_reduction', 100.00, 10.00,
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 1),
    ('default', 'ecommerce', 'user001', 'COUPON002', 'DEF789012', '9折优惠券', 'percent', 'discount', 50.00, NULL,
     NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 1);

-- ============================================================
-- 13. SKU关联分析表（协同过滤）
-- ============================================================
DROP TABLE IF EXISTS `t_sku_association`;
CREATE TABLE `t_sku_association` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`            VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`             VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `source_sku_id`        VARCHAR(128) NOT NULL COMMENT '源SKU ID',
    `target_sku_id`        VARCHAR(128) NOT NULL COMMENT '目标SKU ID',
    `co_occurrence_count`  INT          NOT NULL DEFAULT 0 COMMENT '共现次数',
    `support`              DOUBLE       NOT NULL DEFAULT 0.0 COMMENT '支持度',
    `confidence`           DOUBLE       NOT NULL DEFAULT 0.0 COMMENT '置信度',
    `lift`                 DOUBLE       NOT NULL DEFAULT 0.0 COMMENT '提升度',
    `total_transactions`   INT          NOT NULL DEFAULT 0 COMMENT '总事务数',
    `source_count`         INT          NOT NULL DEFAULT 0 COMMENT '源SKU出现次数',
    `target_count`         INT          NOT NULL DEFAULT 0 COMMENT '目标SKU出现次数',
    `algorithm`            VARCHAR(32)  NOT NULL DEFAULT 'cf' COMMENT '算法:cf-协同过滤,apriori-关联规则',
    `stat_start_time`      DATETIME     DEFAULT NULL COMMENT '统计开始时间',
    `stat_end_time`        DATETIME     DEFAULT NULL COMMENT '统计结束时间',
    `status`               TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-禁用,1-启用',
    `ext_info`             JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku_pair` (`tenant_id`, `biz_type`, `source_sku_id`, `target_sku_id`),
    KEY `idx_source` (`source_sku_id`),
    KEY `idx_target` (`target_sku_id`),
    KEY `idx_confidence` (`confidence`),
    KEY `idx_lift` (`lift`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SKU关联分析表';

-- ============================================================
-- 14. 用户购物车画像表
-- ============================================================
DROP TABLE IF EXISTS `t_user_cart_profile`;
CREATE TABLE `t_user_cart_profile` (
    `id`                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `tenant_id`            VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`             VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`              VARCHAR(128) NOT NULL COMMENT '用户ID',
    `favorite_category_ids`VARCHAR(1024)DEFAULT NULL COMMENT '偏好分类ID列表',
    `favorite_shop_ids`    VARCHAR(1024)DEFAULT NULL COMMENT '偏好店铺ID列表',
    `frequent_skus`        TEXT         DEFAULT NULL COMMENT '常购SKU列表(JSON)',
    `recent_skus`          TEXT         DEFAULT NULL COMMENT '最近加购SKU列表(JSON)',
    `avg_cart_amount`      DECIMAL(18,2)DEFAULT NULL COMMENT '平均购物车金额',
    `avg_cart_size`        INT          DEFAULT NULL COMMENT '平均购物车大小',
    `total_add_count`      INT          NOT NULL DEFAULT 0 COMMENT '总加购次数',
    `total_purchase_count` INT          NOT NULL DEFAULT 0 COMMENT '总购买次数',
    `last_active_time`     DATETIME     DEFAULT NULL COMMENT '最后活跃时间',
    `profile_snapshot`     JSON         DEFAULT NULL COMMENT '画像快照',
    `status`               TINYINT      NOT NULL DEFAULT 1 COMMENT '状态:0-禁用,1-启用',
    `ext_info`             JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`tenant_id`, `biz_type`, `user_id`),
    KEY `idx_last_active` (`last_active_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户购物车画像表';

-- SKU关联示例数据
INSERT INTO `t_sku_association` (`tenant_id`, `biz_type`, `source_sku_id`, `target_sku_id`,
    `co_occurrence_count`, `support`, `confidence`, `lift`, `total_transactions`,
    `source_count`, `target_count`, `algorithm`, `stat_start_time`, `stat_end_time`, `status`)
VALUES
    ('default', 'ecommerce', 'SKU001', 'SKU002', 45, 0.15, 0.75, 2.5, 300, 60, 90, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1),
    ('default', 'ecommerce', 'SKU001', 'SKU003', 38, 0.13, 0.63, 2.1, 300, 60, 55, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1),
    ('default', 'ecommerce', 'SKU002', 'SKU001', 45, 0.15, 0.50, 2.5, 300, 90, 60, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1),
    ('default', 'ecommerce', 'SKU002', 'SKU006', 32, 0.11, 0.36, 2.8, 300, 90, 38, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1),
    ('default', 'ecommerce', 'SKU004', 'SKU005', 28, 0.09, 0.56, 3.2, 300, 50, 35, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1),
    ('default', 'ecommerce', 'SKU003', 'SKU001', 38, 0.13, 0.69, 2.1, 300, 55, 60, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1),
    ('default', 'ecommerce', 'SKU005', 'SKU004', 28, 0.09, 0.80, 3.2, 300, 35, 50, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1),
    ('default', 'ecommerce', 'SKU006', 'SKU002', 32, 0.11, 0.84, 2.8, 300, 38, 90, 'cf',
     DATE_SUB(NOW(), INTERVAL 30 DAY), NOW(), 1);

-- ============================================================
-- 15. 结算快照表（预下单）
-- ============================================================
DROP TABLE IF EXISTS `t_checkout_snapshot`;
CREATE TABLE `t_checkout_snapshot` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `checkout_token`    VARCHAR(64)  NOT NULL COMMENT '结算Token(唯一标识)',
    `tenant_id`         VARCHAR(64)  NOT NULL COMMENT '租户ID',
    `biz_type`          VARCHAR(64)  NOT NULL COMMENT '业务类型',
    `user_id`           VARCHAR(128) NOT NULL COMMENT '用户ID',
    `cart_snapshot`     JSON         NOT NULL COMMENT '购物车快照数据(JSON)',
    `item_count`        INT          NOT NULL DEFAULT 0 COMMENT '商品项数',
    `total_quantity`    INT          NOT NULL DEFAULT 0 COMMENT '商品总数量',
    `total_amount`      DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '商品总金额(原价)',
    `discount_amount`   DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '优惠总金额',
    `pay_amount`        DECIMAL(18,2)NOT NULL DEFAULT 0.00 COMMENT '实付总金额',
    `stock_status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '库存预占状态:0-未预占,1-预占成功,2-预占失败,3-已释放',
    `stock_lock_code`   VARCHAR(128) DEFAULT NULL COMMENT '库存锁定编码(业务系统返回)',
    `status`            TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0-待确认,1-已下单,2-已取消,3-已过期',
    `order_no`          VARCHAR(128) DEFAULT NULL COMMENT '关联订单号',
    `expire_time`       DATETIME     NOT NULL COMMENT '过期时间',
    `client_ip`         VARCHAR(64)  DEFAULT NULL COMMENT '客户端IP',
    `source`            VARCHAR(32)  DEFAULT NULL COMMENT '来源:web/app/mini',
    `ext_info`          JSON         DEFAULT NULL COMMENT '扩展信息',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-未删除,1-已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_checkout_token` (`checkout_token`),
    KEY `idx_user` (`tenant_id`, `biz_type`, `user_id`),
    KEY `idx_status` (`status`),
    KEY `idx_expire` (`expire_time`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算快照表(预下单)';
