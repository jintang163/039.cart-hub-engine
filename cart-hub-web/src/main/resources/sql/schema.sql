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
