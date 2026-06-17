-- ============================================================
-- 购物车行为分析 - ClickHouse 建表脚本
-- 数据库: cart_hub_analytics
-- ============================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS cart_hub_analytics;

USE cart_hub_analytics;

-- ============================================================
-- 1. 购物车事件明细表
-- ============================================================
CREATE TABLE IF NOT EXISTS cart_events (
    event_id String COMMENT '事件唯一ID',
    event_type String COMMENT '事件类型: add_to_cart/remove_from_cart/update_quantity/clear_cart/checkout_click/checkout_create/checkout_confirm/checkout_cancel/view_cart/apply_coupon/remove_coupon/page_view/click',
    timestamp DateTime COMMENT '事件发生时间',
    date Date COMMENT '事件日期（用于分区）',
    hour UInt8 COMMENT '事件发生小时（0-23）',

    tenant_id String COMMENT '租户ID',
    biz_type String COMMENT '业务线类型',
    user_id String COMMENT '用户ID（登录用户）',
    anonymous_id String COMMENT '匿名用户ID',
    session_id String COMMENT '会话ID',

    source String COMMENT '来源: web/h5/miniapp/app',
    client_version String COMMENT '客户端版本',
    page_url String COMMENT '页面URL',
    page_title String COMMENT '页面标题',
    referrer String COMMENT '来源页面',
    user_agent String COMMENT '用户代理',
    client_ip String COMMENT '客户端IP',

    sku_id String COMMENT 'SKU ID',
    spu_id String COMMENT 'SPU ID',
    category_id String COMMENT '商品分类ID',
    category_name String COMMENT '商品分类名称',
    shop_id String COMMENT '店铺ID',
    item_name String COMMENT '商品名称',
    item_image String COMMENT '商品图片',

    unit_price Decimal(18,2) COMMENT '商品单价',
    original_price Decimal(18,2) COMMENT '商品原价',
    quantity Int32 COMMENT '商品数量',
    old_quantity Int32 COMMENT '修改前数量',
    new_quantity Int32 COMMENT '修改后数量',

    checkout_token String COMMENT '结算Token',
    cart_total_amount Decimal(18,2) COMMENT '购物车总金额',
    cart_item_count Int32 COMMENT '购物车商品数量',

    coupon_id String COMMENT '优惠券ID',
    coupon_code String COMMENT '优惠券码',
    discount_amount Decimal(18,2) COMMENT '优惠金额',

    element_id String COMMENT '点击元素ID',
    element_class String COMMENT '点击元素class',
    element_text String COMMENT '点击元素文本',
    position Int32 COMMENT '位置（如商品列表位置）',
    duration Int64 COMMENT '持续时间（毫秒）',

    properties String COMMENT '自定义属性（JSON格式）',
    trace_id String COMMENT '链路追踪ID',
    ext_info String COMMENT '扩展信息（JSON格式）')
ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (tenant_id, biz_type, event_type, date, timestamp)
SETTINGS index_granularity = 8192
COMMENT '购物车行为事件明细表';

-- ============================================================
-- 2. 结算会话表
-- ============================================================
CREATE TABLE IF NOT EXISTS checkout_sessions (
    session_id String COMMENT '会话ID',
    tenant_id String COMMENT '租户ID',
    biz_type String COMMENT '业务线类型',
    user_id String COMMENT '用户ID',
    anonymous_id String COMMENT '匿名用户ID',
    checkout_token String COMMENT '结算Token',
    start_time DateTime COMMENT '开始时间',
    end_time DateTime COMMENT '结束时间',
    duration_seconds Int64 COMMENT '结算时长（秒）',
    status String COMMENT '状态: completed/abandoned/cancelled',
    item_count Int32 COMMENT '商品数量',
    total_amount Decimal(18,2) COMMENT '总金额',
    discount_amount Decimal(18,2) COMMENT '优惠金额',
    pay_amount Decimal(18,2) COMMENT '实付金额',
    coupon_id String COMMENT '使用的优惠券ID',
    source String COMMENT '来源',
    date Date COMMENT '日期')
ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (tenant_id, biz_type, date, start_time)
SETTINGS index_granularity = 8192
COMMENT '结算会话表';

-- ============================================================
-- 3. 商品加购统计（物化视图，可选）
-- ============================================================
-- CREATE MATERIALIZED VIEW IF NOT EXISTS product_add_cart_mv
-- ENGINE = SummingMergeTree()
-- PARTITION BY toYYYYMM(date)
-- ORDER BY (tenant_id, biz_type, date, sku_id)
-- AS SELECT
--     tenant_id,
--     biz_type,
--     date,
--     sku_id,
--     spu_id,
--     category_id,
--     category_name,
--     item_name,
--     count() as add_count,
--     uniq(user_id) as add_user_count,
--     sum(quantity) as add_quantity,
--     sum(unit_price * quantity) as add_amount
-- FROM cart_events
-- WHERE event_type = 'add_to_cart'
-- GROUP BY tenant_id, biz_type, date, sku_id, spu_id, category_id, category_name, item_name;

-- ============================================================
-- 4. 每日统计汇总表（物化视图，可选）
-- ============================================================
-- CREATE MATERIALIZED VIEW IF NOT EXISTS daily_stats_mv
-- ENGINE = SummingMergeTree()
-- PARTITION BY toYYYYMM(date)
-- ORDER BY (tenant_id, biz_type, date)
-- AS SELECT
--     tenant_id,
--     biz_type,
--     date,
--     sum(if(event_type = 'add_to_cart', 1, 0)) as add_to_cart_count,
--     sum(if(event_type = 'remove_from_cart', 1, 0)) as remove_from_cart_count,
--     sum(if(event_type = 'update_quantity', 1, 0)) as update_quantity_count,
--     sum(if(event_type = 'checkout_create', 1, 0)) as checkout_create_count,
--     sum(if(event_type = 'checkout_confirm', 1, 0)) as checkout_confirm_count,
--     uniq(user_id) as active_user_count,
--     uniq(session_id) as session_count
-- FROM cart_events
-- GROUP BY tenant_id, biz_type, date;

-- ============================================================
-- 示例查询
-- ============================================================

-- 1. 热门商品加购次数 Top N
-- SELECT
--     sku_id,
--     item_name,
--     count() as add_count,
--     uniq(user_id) as add_user_count
-- FROM cart_events
-- WHERE event_type = 'add_to_cart'
--   AND tenant_id = 'default'
--   AND date >= '2024-01-01' AND date <= '2024-01-31'
-- GROUP BY sku_id, item_name
-- ORDER BY add_count DESC
-- LIMIT 20;

-- 2. 购物车放弃率
-- SELECT
--     date,
--     uniq(if(event_type = 'checkout_create', checkout_token, null)) as checkout_started,
--     uniq(if(event_type = 'checkout_confirm', checkout_token, null)) as checkout_completed,
--     checkout_started - checkout_completed as abandoned,
--     round(abandoned * 100.0 / checkout_started, 2) as abandonment_rate
-- FROM cart_events
-- WHERE event_type IN ('checkout_create', 'checkout_confirm')
--   AND tenant_id = 'default'
--   AND date >= '2024-01-01' AND date <= '2024-01-31'
-- GROUP BY date
-- ORDER BY date ASC;

-- 3. 平均结算时长
-- SELECT
--     date,
--     avg(duration_seconds) as avg_duration_seconds,
--     median(duration_seconds) as median_duration_seconds,
--     quantile(0.9)(duration_seconds) as p90_duration_seconds
-- FROM checkout_sessions
-- WHERE status = 'completed'
--   AND tenant_id = 'default'
--   AND date >= '2024-01-01' AND date <= '2024-01-31'
-- GROUP BY date
-- ORDER BY date ASC;

-- 4. 按业务线下钻
-- SELECT
--     biz_type,
--     count() as add_count,
--     uniq(user_id) as add_user_count
-- FROM cart_events
-- WHERE event_type = 'add_to_cart'
--   AND tenant_id = 'default'
--   AND date >= '2024-01-01' AND date <= '2024-01-31'
-- GROUP BY biz_type
-- ORDER BY add_count DESC;

-- 5. 按商品分类下钻
-- SELECT
--     category_id,
--     category_name,
--     count() as add_count,
--     uniq(user_id) as add_user_count
-- FROM cart_events
-- WHERE event_type = 'add_to_cart'
--   AND tenant_id = 'default'
--   AND date >= '2024-01-01' AND date <= '2024-01-31'
--   AND category_id != ''
-- GROUP BY category_id, category_name
-- ORDER BY add_count DESC
-- LIMIT 20;

-- 6. 按小时分布
-- SELECT
--     hour,
--     count() as event_count
-- FROM cart_events
-- WHERE event_type = 'add_to_cart'
--   AND tenant_id = 'default'
--   AND date = today()
-- GROUP BY hour
-- ORDER BY hour ASC;
