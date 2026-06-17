package com.carhub.service;

import com.alibaba.fastjson.JSON;
import com.carhub.common.context.CartContextHolder;
import com.carhub.config.AnalyticsProperties;
import com.carhub.domain.dto.CartEventDTO;
import com.carhub.domain.dto.AnalyticsQueryDTO;
import com.carhub.domain.vo.AnalyticsOverviewVO;
import com.carhub.domain.vo.CartAbandonmentVO;
import com.carhub.domain.vo.CheckoutDurationVO;
import com.carhub.domain.vo.ProductAnalyticsVO;
import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartAnalyticsService {

    private final ClickHouseDataSource clickHouseDataSource;
    private final AnalyticsProperties analyticsProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String TABLE_CART_EVENTS = "cart_events";
    private static final String TABLE_CHECKOUT_SESSIONS = "checkout_sessions";

    @Resource
    private CartStatisticsService cartStatisticsService;

    @PostConstruct
    public void init() {
        try {
            ensureTablesExist();
            log.info("CartAnalyticsService initialized, tables ensured");
        } catch (Exception e) {
            log.warn("Failed to ensure ClickHouse tables exist, will retry later", e);
        }
    }

    private void ensureTablesExist() {
        try (Connection conn = clickHouseDataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            String createEventsTable = "CREATE TABLE IF NOT EXISTS " + TABLE_CART_EVENTS + " (" +
                    "event_id String," +
                    "event_type String," +
                    "timestamp DateTime," +
                    "date Date," +
                    "hour UInt8," +
                    "tenant_id String," +
                    "biz_type String," +
                    "user_id String," +
                    "anonymous_id String," +
                    "session_id String," +
                    "source String," +
                    "client_version String," +
                    "page_url String," +
                    "page_title String," +
                    "referrer String," +
                    "user_agent String," +
                    "client_ip String," +
                    "sku_id String," +
                    "spu_id String," +
                    "category_id String," +
                    "category_name String," +
                    "shop_id String," +
                    "item_name String," +
                    "item_image String," +
                    "unit_price Decimal(18,2)," +
                    "original_price Decimal(18,2)," +
                    "quantity Int32," +
                    "old_quantity Int32," +
                    "new_quantity Int32," +
                    "checkout_token String," +
                    "cart_total_amount Decimal(18,2)," +
                    "cart_item_count Int32," +
                    "coupon_id String," +
                    "coupon_code String," +
                    "discount_amount Decimal(18,2)," +
                    "element_id String," +
                    "element_class String," +
                    "element_text String," +
                    "position Int32," +
                    "duration Int64," +
                    "properties String," +
                    "trace_id String," +
                    "ext_info String" +
                    ") ENGINE = MergeTree() " +
                    "PARTITION BY toYYYYMM(date) " +
                    "ORDER BY (tenant_id, biz_type, event_type, date, timestamp) " +
                    "SETTINGS index_granularity = 8192";

            stmt.execute(createEventsTable);

            String createSessionsTable = "CREATE TABLE IF NOT EXISTS " + TABLE_CHECKOUT_SESSIONS + " (" +
                    "session_id String," +
                    "tenant_id String," +
                    "biz_type String," +
                    "user_id String," +
                    "anonymous_id String," +
                    "checkout_token String," +
                    "start_time DateTime," +
                    "end_time DateTime," +
                    "duration_seconds Int64," +
                    "status String," +
                    "item_count Int32," +
                    "total_amount Decimal(18,2)," +
                    "discount_amount Decimal(18,2)," +
                    "pay_amount Decimal(18,2)," +
                    "coupon_id String," +
                    "source String," +
                    "date Date" +
                    ") ENGINE = MergeTree() " +
                    "PARTITION BY toYYYYMM(date) " +
                    "ORDER BY (tenant_id, biz_type, date, start_time) " +
                    "SETTINGS index_granularity = 8192";

            stmt.execute(createSessionsTable);

            log.info("ClickHouse tables ensured: {}, {}", TABLE_CART_EVENTS, TABLE_CHECKOUT_SESSIONS);
        } catch (Exception e) {
            log.error("Failed to create ClickHouse tables", e);
        }
    }

    public void trackEvent(CartEventDTO event) {
        if (event == null || StringUtils.isBlank(event.getEventType())) {
            return;
        }

        enrichEvent(event);

        String eventJson = JSON.toJSONString(event);
        String topic = analyticsProperties.getKafkaTopic();

        try {
            kafkaTemplate.send(topic, event.getEventType(), eventJson);
            log.debug("Event sent to Kafka: topic={}, eventType={}, eventId={}",
                    topic, event.getEventType(), event.getEventId());
        } catch (Exception e) {
            log.error("Failed to send event to Kafka: eventType={}", event.getEventType(), e);
        }
    }

    @Async
    public void trackEventAsync(CartEventDTO event) {
        trackEvent(event);
    }

    public void trackEventBatch(List<CartEventDTO> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        String topic = analyticsProperties.getKafkaTopic();
        for (CartEventDTO event : events) {
            try {
                enrichEvent(event);
                String eventJson = JSON.toJSONString(event);
                kafkaTemplate.send(topic, event.getEventType(), eventJson);
            } catch (Exception e) {
                log.error("Failed to send batch event to Kafka", e);
            }
        }
    }

    private void enrichEvent(CartEventDTO event) {
        if (StringUtils.isBlank(event.getEventId())) {
            event.setEventId(UUID.randomUUID().toString().replace("-", ""));
        }

        if (event.getTimestamp() == null) {
            event.setTimestamp(System.currentTimeMillis());
        }

        if (StringUtils.isBlank(event.getTenantId())) {
            event.setTenantId(CartContextHolder.getTenantId());
        }
        if (StringUtils.isBlank(event.getBizType())) {
            event.setBizType(CartContextHolder.getBizType());
        }
        if (StringUtils.isBlank(event.getUserId())) {
            event.setUserId(CartContextHolder.getUserId());
        }
        if (StringUtils.isBlank(event.getSource())) {
            event.setSource(CartContextHolder.getSource());
        }
        if (StringUtils.isBlank(event.getClientIp())) {
            event.setClientIp(CartContextHolder.getClientIp());
        }

        LocalDateTime eventTime = new java.sql.Timestamp(event.getTimestamp()).toLocalDateTime();
        event.setTraceId(event.getTraceId() != null ? event.getTraceId() : "");
    }

    public void insertEventToClickHouse(CartEventDTO event) {
        String sql = "INSERT INTO " + TABLE_CART_EVENTS + " (" +
                "event_id, event_type, timestamp, date, hour, " +
                "tenant_id, biz_type, user_id, anonymous_id, session_id, " +
                "source, client_version, page_url, page_title, referrer, " +
                "user_agent, client_ip, sku_id, spu_id, category_id, " +
                "category_name, shop_id, item_name, item_image, unit_price, " +
                "original_price, quantity, old_quantity, new_quantity, checkout_token, " +
                "cart_total_amount, cart_item_count, coupon_id, coupon_code, discount_amount, " +
                "element_id, element_class, element_text, position, duration, " +
                "properties, trace_id, ext_info" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            LocalDateTime eventTime = new java.sql.Timestamp(event.getTimestamp()).toLocalDateTime();
            LocalDate eventDate = eventTime.toLocalDate();

            int idx = 1;
            pstmt.setString(idx++, event.getEventId());
            pstmt.setString(idx++, event.getEventType());
            pstmt.setTimestamp(idx++, Timestamp.valueOf(eventTime));
            pstmt.setDate(idx++, Date.valueOf(eventDate));
            pstmt.setInt(idx++, eventTime.getHour());

            pstmt.setString(idx++, defaultString(event.getTenantId()));
            pstmt.setString(idx++, defaultString(event.getBizType()));
            pstmt.setString(idx++, defaultString(event.getUserId()));
            pstmt.setString(idx++, defaultString(event.getAnonymousId()));
            pstmt.setString(idx++, defaultString(event.getSessionId()));

            pstmt.setString(idx++, defaultString(event.getSource()));
            pstmt.setString(idx++, defaultString(event.getClientVersion()));
            pstmt.setString(idx++, defaultString(event.getPageUrl()));
            pstmt.setString(idx++, defaultString(event.getPageTitle()));
            pstmt.setString(idx++, defaultString(event.getReferrer()));

            pstmt.setString(idx++, defaultString(event.getUserAgent()));
            pstmt.setString(idx++, defaultString(event.getClientIp()));
            pstmt.setString(idx++, defaultString(event.getSkuId()));
            pstmt.setString(idx++, defaultString(event.getSpuId()));
            pstmt.setString(idx++, defaultString(event.getCategoryId()));

            pstmt.setString(idx++, defaultString(event.getCategoryName()));
            pstmt.setString(idx++, defaultString(event.getShopId()));
            pstmt.setString(idx++, defaultString(event.getItemName()));
            pstmt.setString(idx++, defaultString(event.getItemImage()));
            pstmt.setBigDecimal(idx++, event.getUnitPrice() != null ? event.getUnitPrice() : BigDecimal.ZERO);

            pstmt.setBigDecimal(idx++, event.getOriginalPrice() != null ? event.getOriginalPrice() : BigDecimal.ZERO);
            pstmt.setInt(idx++, event.getQuantity() != null ? event.getQuantity() : 0);
            pstmt.setInt(idx++, event.getOldQuantity() != null ? event.getOldQuantity() : 0);
            pstmt.setInt(idx++, event.getNewQuantity() != null ? event.getNewQuantity() : 0);
            pstmt.setString(idx++, defaultString(event.getCheckoutToken()));

            pstmt.setBigDecimal(idx++, event.getCartTotalAmount() != null ? event.getCartTotalAmount() : BigDecimal.ZERO);
            pstmt.setInt(idx++, event.getCartItemCount() != null ? event.getCartItemCount() : 0);
            pstmt.setString(idx++, defaultString(event.getCouponId()));
            pstmt.setString(idx++, defaultString(event.getCouponCode()));
            pstmt.setBigDecimal(idx++, event.getDiscountAmount() != null ? event.getDiscountAmount() : BigDecimal.ZERO);

            pstmt.setString(idx++, defaultString(event.getElementId()));
            pstmt.setString(idx++, defaultString(event.getElementClass()));
            pstmt.setString(idx++, defaultString(event.getElementText()));
            pstmt.setInt(idx++, event.getPosition() != null ? event.getPosition() : 0);
            pstmt.setLong(idx++, event.getDuration() != null ? event.getDuration() : 0L);

            pstmt.setString(idx++, event.getProperties() != null ? JSON.toJSONString(event.getProperties()) : "");
            pstmt.setString(idx++, defaultString(event.getTraceId()));
            pstmt.setString(idx++, event.getExtInfo() != null ? JSON.toJSONString(event.getExtInfo()) : "");

            pstmt.executeUpdate();
            log.debug("Event inserted to ClickHouse: eventId={}, eventType={}", event.getEventId(), event.getEventType());
        } catch (Exception e) {
            log.error("Failed to insert event to ClickHouse: eventType={}", event.getEventType(), e);
        }
    }

    public void insertEventsBatch(List<CartEventDTO> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO " + TABLE_CART_EVENTS + " (" +
                "event_id, event_type, timestamp, date, hour, " +
                "tenant_id, biz_type, user_id, anonymous_id, session_id, " +
                "source, client_version, page_url, page_title, referrer, " +
                "user_agent, client_ip, sku_id, spu_id, category_id, " +
                "category_name, shop_id, item_name, item_image, unit_price, " +
                "original_price, quantity, old_quantity, new_quantity, checkout_token, " +
                "cart_total_amount, cart_item_count, coupon_id, coupon_code, discount_amount, " +
                "element_id, element_class, element_text, position, duration, " +
                "properties, trace_id, ext_info" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (CartEventDTO event : events) {
                LocalDateTime eventTime = new java.sql.Timestamp(event.getTimestamp()).toLocalDateTime();
                LocalDate eventDate = eventTime.toLocalDate();

                int idx = 1;
                pstmt.setString(idx++, event.getEventId());
                pstmt.setString(idx++, event.getEventType());
                pstmt.setTimestamp(idx++, Timestamp.valueOf(eventTime));
                pstmt.setDate(idx++, Date.valueOf(eventDate));
                pstmt.setInt(idx++, eventTime.getHour());

                pstmt.setString(idx++, defaultString(event.getTenantId()));
                pstmt.setString(idx++, defaultString(event.getBizType()));
                pstmt.setString(idx++, defaultString(event.getUserId()));
                pstmt.setString(idx++, defaultString(event.getAnonymousId()));
                pstmt.setString(idx++, defaultString(event.getSessionId()));

                pstmt.setString(idx++, defaultString(event.getSource()));
                pstmt.setString(idx++, defaultString(event.getClientVersion()));
                pstmt.setString(idx++, defaultString(event.getPageUrl()));
                pstmt.setString(idx++, defaultString(event.getPageTitle()));
                pstmt.setString(idx++, defaultString(event.getReferrer()));

                pstmt.setString(idx++, defaultString(event.getUserAgent()));
                pstmt.setString(idx++, defaultString(event.getClientIp()));
                pstmt.setString(idx++, defaultString(event.getSkuId()));
                pstmt.setString(idx++, defaultString(event.getSpuId()));
                pstmt.setString(idx++, defaultString(event.getCategoryId()));

                pstmt.setString(idx++, defaultString(event.getCategoryName()));
                pstmt.setString(idx++, defaultString(event.getShopId()));
                pstmt.setString(idx++, defaultString(event.getItemName()));
                pstmt.setString(idx++, defaultString(event.getItemImage()));
                pstmt.setBigDecimal(idx++, event.getUnitPrice() != null ? event.getUnitPrice() : BigDecimal.ZERO);

                pstmt.setBigDecimal(idx++, event.getOriginalPrice() != null ? event.getOriginalPrice() : BigDecimal.ZERO);
                pstmt.setInt(idx++, event.getQuantity() != null ? event.getQuantity() : 0);
                pstmt.setInt(idx++, event.getOldQuantity() != null ? event.getOldQuantity() : 0);
                pstmt.setInt(idx++, event.getNewQuantity() != null ? event.getNewQuantity() : 0);
                pstmt.setString(idx++, defaultString(event.getCheckoutToken()));

                pstmt.setBigDecimal(idx++, event.getCartTotalAmount() != null ? event.getCartTotalAmount() : BigDecimal.ZERO);
                pstmt.setInt(idx++, event.getCartItemCount() != null ? event.getCartItemCount() : 0);
                pstmt.setString(idx++, defaultString(event.getCouponId()));
                pstmt.setString(idx++, defaultString(event.getCouponCode()));
                pstmt.setBigDecimal(idx++, event.getDiscountAmount() != null ? event.getDiscountAmount() : BigDecimal.ZERO);

                pstmt.setString(idx++, defaultString(event.getElementId()));
                pstmt.setString(idx++, defaultString(event.getElementClass()));
                pstmt.setString(idx++, defaultString(event.getElementText()));
                pstmt.setInt(idx++, event.getPosition() != null ? event.getPosition() : 0);
                pstmt.setLong(idx++, event.getDuration() != null ? event.getDuration() : 0L);

                pstmt.setString(idx++, event.getProperties() != null ? JSON.toJSONString(event.getProperties()) : "");
                pstmt.setString(idx++, defaultString(event.getTraceId()));
                pstmt.setString(idx++, event.getExtInfo() != null ? JSON.toJSONString(event.getExtInfo()) : "");

                pstmt.addBatch();
            }

            pstmt.executeBatch();
            log.info("Batch inserted {} events to ClickHouse", events.size());
        } catch (Exception e) {
            log.error("Failed to batch insert events to ClickHouse, count={}", events.size(), e);
        }
    }

    private String defaultString(String value) {
        return StringUtils.defaultIfBlank(value, "");
    }

    public AnalyticsOverviewVO getOverview(AnalyticsQueryDTO query) {
        String tenantId = StringUtils.defaultIfBlank(query.getTenantId(), CartContextHolder.getTenantId());
        String bizType = resolveBizType(query.getBizType());
        String startDate = StringUtils.defaultIfBlank(query.getStartDate(),
                LocalDate.now().minusDays(30).format(DATE_FMT));
        String endDate = StringUtils.defaultIfBlank(query.getEndDate(),
                LocalDate.now().format(DATE_FMT));
        int topN = query.getTopN() != null ? query.getTopN() : 10;

        AnalyticsOverviewVO overview = AnalyticsOverviewVO.builder()
                .tenantId(tenantId)
                .bizType(bizType)
                .startDate(startDate)
                .endDate(endDate)
                .build();

        Map<String, Long> eventCounts = getEventCounts(tenantId, bizType, startDate, endDate);
        overview.setTotalAddToCartCount(eventCounts.getOrDefault("add_to_cart", 0L));
        overview.setTotalRemoveFromCartCount(eventCounts.getOrDefault("remove_from_cart", 0L));
        overview.setTotalCheckoutCount(eventCounts.getOrDefault("checkout_create", 0L));
        overview.setTotalPurchaseCount(eventCounts.getOrDefault("checkout_confirm", 0L));
        overview.setTotalAddToCartUserCount(eventCounts.getOrDefault("add_to_cart_users", 0L));

        Map<String, BigDecimal> amountStats = getAmountStats(tenantId, bizType, startDate, endDate);
        overview.setTotalCartAmount(amountStats.getOrDefault("total_cart_amount", BigDecimal.ZERO));
        overview.setTotalPurchaseAmount(amountStats.getOrDefault("total_purchase_amount", BigDecimal.ZERO));
        overview.setAvgCartSize(amountStats.getOrDefault("avg_cart_size", BigDecimal.ZERO));
        overview.setAvgCartAmount(amountStats.getOrDefault("avg_cart_amount", BigDecimal.ZERO));

        long addCount = overview.getTotalAddToCartCount();
        long purchaseCount = overview.getTotalPurchaseCount();
        long checkoutCount = overview.getTotalCheckoutCount();

        if (checkoutCount > 0) {
            overview.setCartAbandonmentRate(BigDecimal.valueOf((checkoutCount - purchaseCount) * 100.0 / checkoutCount)
                    .setScale(2, RoundingMode.HALF_UP));
            overview.setCheckoutConversionRate(BigDecimal.valueOf(purchaseCount * 100.0 / checkoutCount)
                    .setScale(2, RoundingMode.HALF_UP));
        } else {
            overview.setCartAbandonmentRate(BigDecimal.ZERO);
            overview.setCheckoutConversionRate(BigDecimal.ZERO);
        }

        if (addCount > 0) {
            overview.setAddToCartRate(BigDecimal.valueOf(purchaseCount * 100.0 / addCount)
                    .setScale(2, RoundingMode.HALF_UP));
        } else {
            overview.setAddToCartRate(BigDecimal.ZERO);
        }

        overview.setAvgCheckoutDurationSeconds(getAvgCheckoutDuration(tenantId, bizType, startDate, endDate));
        overview.setTopProducts(getTopProducts(tenantId, bizType, startDate, endDate, topN, "add_to_cart"));
        overview.setDailyTrend(getDailyTrend(tenantId, bizType, startDate, endDate));
        overview.setBreakdownBySource(getBreakdownBySource(tenantId, bizType, startDate, endDate));
        overview.setBreakdownByCategory(getBreakdownByCategory(tenantId, bizType, startDate, endDate));

        return overview;
    }

    private Map<String, Long> getEventCounts(String tenantId, String bizType, String startDate, String endDate) {
        Map<String, Long> result = new ConcurrentHashMap<>();
        String sql = "SELECT event_type, count(*) as cnt, count(distinct user_id) as user_cnt " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        sql += "GROUP BY event_type";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String eventType = rs.getString("event_type");
                long count = rs.getLong("cnt");
                long userCount = rs.getLong("user_cnt");
                result.put(eventType, count);
                result.put(eventType + "_users", userCount);
            }
        } catch (Exception e) {
            log.error("Failed to get event counts", e);
        }
        return result;
    }

    private Map<String, BigDecimal> getAmountStats(String tenantId, String bizType, String startDate, String endDate) {
        Map<String, BigDecimal> result = new HashMap<>();
        String sql = "SELECT " +
                "sum(if(event_type = 'add_to_cart', unit_price * quantity, 0)) as total_cart_amount, " +
                "sum(if(event_type = 'checkout_confirm', cart_total_amount, 0)) as total_purchase_amount, " +
                "avg(if(event_type = 'add_to_cart', quantity, 0)) as avg_quantity, " +
                "count(distinct if(event_type = 'add_to_cart', session_id, null)) as add_sessions " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                result.put("total_cart_amount", rs.getBigDecimal("total_cart_amount") != null ?
                        rs.getBigDecimal("total_cart_amount") : BigDecimal.ZERO);
                result.put("total_purchase_amount", rs.getBigDecimal("total_purchase_amount") != null ?
                        rs.getBigDecimal("total_purchase_amount") : BigDecimal.ZERO);
                BigDecimal avgQty = rs.getBigDecimal("avg_quantity");
                result.put("avg_cart_size", avgQty != null ? avgQty.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

                long addSessions = rs.getLong("add_sessions");
                BigDecimal totalCartAmount = result.get("total_cart_amount");
                if (addSessions > 0 && totalCartAmount != null) {
                    result.put("avg_cart_amount", totalCartAmount.divide(
                            BigDecimal.valueOf(addSessions), 2, RoundingMode.HALF_UP));
                } else {
                    result.put("avg_cart_amount", BigDecimal.ZERO);
                }
            }
        } catch (Exception e) {
            log.error("Failed to get amount stats", e);
            result.put("total_cart_amount", BigDecimal.ZERO);
            result.put("total_purchase_amount", BigDecimal.ZERO);
            result.put("avg_cart_size", BigDecimal.ZERO);
            result.put("avg_cart_amount", BigDecimal.ZERO);
        }
        return result;
    }

    private BigDecimal getAvgCheckoutDuration(String tenantId, String bizType, String startDate, String endDate) {
        String sql = "SELECT avg(duration_seconds) as avg_duration " +
                "FROM " + TABLE_CHECKOUT_SESSIONS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? AND status = 'completed' ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                BigDecimal avg = rs.getBigDecimal("avg_duration");
                return avg != null ? avg.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            }
        } catch (Exception e) {
            log.error("Failed to get avg checkout duration", e);
        }
        return BigDecimal.ZERO;
    }

    public List<ProductAnalyticsVO> getTopProducts(String tenantId, String bizType, String startDate, String endDate,
                                                    int topN, String sortBy) {
        List<ProductAnalyticsVO> result = new ArrayList<>();

        String sql = "SELECT " +
                "sku_id, spu_id, category_id, category_name, item_name, item_image, " +
                "sum(if(event_type = 'add_to_cart', 1, 0)) as add_count, " +
                "count(distinct if(event_type = 'add_to_cart', user_id, null)) as add_user_count, " +
                "sum(if(event_type = 'remove_from_cart', 1, 0)) as remove_count, " +
                "sum(if(event_type = 'checkout_confirm', 1, 0)) as purchase_count, " +
                "sum(if(event_type = 'add_to_cart', unit_price * quantity, 0)) as add_amount " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? AND sku_id != '' ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        sql += "GROUP BY sku_id, spu_id, category_id, category_name, item_name, item_image ";

        String orderColumn = "add_count";
        if ("purchase_count".equals(sortBy)) {
            orderColumn = "purchase_count";
        } else if ("remove_count".equals(sortBy)) {
            orderColumn = "remove_count";
        } else if ("add_amount".equals(sortBy)) {
            orderColumn = "add_amount";
        }
        sql += "ORDER BY " + orderColumn + " DESC LIMIT ?";
        params.add(topN);

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            long rank = 0;
            while (rs.next()) {
                rank++;
                long addCount = rs.getLong("add_count");
                long purchaseCount = rs.getLong("purchase_count");

                ProductAnalyticsVO vo = ProductAnalyticsVO.builder()
                        .skuId(rs.getString("sku_id"))
                        .spuId(rs.getString("spu_id"))
                        .categoryId(rs.getString("category_id"))
                        .categoryName(rs.getString("category_name"))
                        .itemName(rs.getString("item_name"))
                        .itemImage(rs.getString("item_image"))
                        .addToCartCount(addCount)
                        .addToCartUserCount(rs.getLong("add_user_count"))
                        .removeFromCartCount(rs.getLong("remove_count"))
                        .purchaseCount(purchaseCount)
                        .addToCartAmount(rs.getBigDecimal("add_amount"))
                        .conversionRate(addCount > 0 ?
                                BigDecimal.valueOf(purchaseCount * 100.0 / addCount).setScale(2, RoundingMode.HALF_UP) :
                                BigDecimal.ZERO)
                        .rank(rank)
                        .build();
                result.add(vo);
            }
        } catch (Exception e) {
            log.error("Failed to get top products", e);
        }
        return result;
    }

    public List<CartAbandonmentVO> getCartAbandonmentTrend(AnalyticsQueryDTO query) {
        String tenantId = StringUtils.defaultIfBlank(query.getTenantId(), CartContextHolder.getTenantId());
        String bizType = resolveBizType(query.getBizType());
        String startDate = StringUtils.defaultIfBlank(query.getStartDate(),
                LocalDate.now().minusDays(30).format(DATE_FMT));
        String endDate = StringUtils.defaultIfBlank(query.getEndDate(),
                LocalDate.now().format(DATE_FMT));

        List<CartAbandonmentVO> result = new ArrayList<>();

        String sql = "SELECT " +
                "date, " +
                "count(distinct if(event_type = 'add_to_cart', session_id, null)) as cart_sessions, " +
                "count(distinct if(event_type = 'checkout_create', checkout_token, null)) as checkout_started, " +
                "count(distinct if(event_type = 'checkout_confirm', checkout_token, null)) as checkout_completed, " +
                "sum(if(event_type = 'add_to_cart', quantity, 0)) as total_items, " +
                "sum(if(event_type = 'add_to_cart', unit_price * quantity, 0)) as total_amount " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        sql += "GROUP BY date ORDER BY date ASC";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                long cartSessions = rs.getLong("cart_sessions");
                long checkoutStarted = rs.getLong("checkout_started");
                long checkoutCompleted = rs.getLong("checkout_completed");
                long abandoned = checkoutStarted - checkoutCompleted;
                long totalItems = rs.getLong("total_items");
                BigDecimal totalAmount = rs.getBigDecimal("total_amount");

                BigDecimal abandonmentRate = checkoutStarted > 0 ?
                        BigDecimal.valueOf(abandoned * 100.0 / checkoutStarted).setScale(2, RoundingMode.HALF_UP) :
                        BigDecimal.ZERO;
                BigDecimal conversionRate = checkoutStarted > 0 ?
                        BigDecimal.valueOf(checkoutCompleted * 100.0 / checkoutStarted).setScale(2, RoundingMode.HALF_UP) :
                        BigDecimal.ZERO;

                CartAbandonmentVO vo = CartAbandonmentVO.builder()
                        .statDate(rs.getString("date"))
                        .totalCartSessions(cartSessions)
                        .checkoutStartedCount(checkoutStarted)
                        .checkoutCompletedCount(checkoutCompleted)
                        .abandonedCount(abandoned)
                        .abandonmentRate(abandonmentRate)
                        .checkoutConversionRate(conversionRate)
                        .abandonedItemCount(totalItems)
                        .abandonedAmount(totalAmount)
                        .avgAbandonedItems(cartSessions > 0 ?
                                BigDecimal.valueOf((double) totalItems / cartSessions).setScale(2, RoundingMode.HALF_UP) :
                                BigDecimal.ZERO)
                        .avgAbandonedAmount(cartSessions > 0 ?
                                totalAmount.divide(BigDecimal.valueOf(cartSessions), 2, RoundingMode.HALF_UP) :
                                BigDecimal.ZERO)
                        .build();
                result.add(vo);
            }
        } catch (Exception e) {
            log.error("Failed to get cart abandonment trend", e);
        }
        return result;
    }

    public List<CheckoutDurationVO> getCheckoutDurationTrend(AnalyticsQueryDTO query) {
        String tenantId = StringUtils.defaultIfBlank(query.getTenantId(), CartContextHolder.getTenantId());
        String bizType = resolveBizType(query.getBizType());
        String startDate = StringUtils.defaultIfBlank(query.getStartDate(),
                LocalDate.now().minusDays(30).format(DATE_FMT));
        String endDate = StringUtils.defaultIfBlank(query.getEndDate(),
                LocalDate.now().format(DATE_FMT));

        List<CheckoutDurationVO> result = new ArrayList<>();

        String sql = "SELECT " +
                "date, " +
                "count(*) as total_checkouts, " +
                "sum(if(status = 'completed', 1, 0)) as completed_checkouts, " +
                "sum(if(status = 'abandoned', 1, 0)) as abandoned_checkouts, " +
                "avg(duration_seconds) as avg_duration, " +
                "avg(item_count) as avg_items, " +
                "avg(total_amount) as avg_amount " +
                "FROM " + TABLE_CHECKOUT_SESSIONS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        sql += "GROUP BY date ORDER BY date ASC";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                long total = rs.getLong("total_checkouts");
                long completed = rs.getLong("completed_checkouts");

                CheckoutDurationVO vo = CheckoutDurationVO.builder()
                        .statDate(rs.getString("date"))
                        .totalCheckouts(total)
                        .completedCheckouts(completed)
                        .abandonedCheckouts(rs.getLong("abandoned_checkouts"))
                        .avgDurationSeconds(rs.getBigDecimal("avg_duration") != null ?
                                rs.getBigDecimal("avg_duration").setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                        .avgItemCount(rs.getBigDecimal("avg_items") != null ?
                                rs.getBigDecimal("avg_items").setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                        .avgAmount(rs.getBigDecimal("avg_amount") != null ?
                                rs.getBigDecimal("avg_amount").setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                        .successRate(total > 0 ?
                                BigDecimal.valueOf(completed * 100.0 / total).setScale(2, RoundingMode.HALF_UP) :
                                BigDecimal.ZERO)
                        .build();
                result.add(vo);
            }
        } catch (Exception e) {
            log.error("Failed to get checkout duration trend", e);
        }
        return result;
    }

    private List<Map<String, Object>> getDailyTrend(String tenantId, String bizType, String startDate, String endDate) {
        List<Map<String, Object>> result = new ArrayList<>();

        String sql = "SELECT " +
                "date, " +
                "sum(if(event_type = 'add_to_cart', 1, 0)) as add_count, " +
                "sum(if(event_type = 'remove_from_cart', 1, 0)) as remove_count, " +
                "sum(if(event_type = 'checkout_create', 1, 0)) as checkout_count, " +
                "sum(if(event_type = 'checkout_confirm', 1, 0)) as purchase_count, " +
                "count(distinct user_id) as active_users " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        sql += "GROUP BY date ORDER BY date ASC";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", rs.getString("date"));
                item.put("addCount", rs.getLong("add_count"));
                item.put("removeCount", rs.getLong("remove_count"));
                item.put("checkoutCount", rs.getLong("checkout_count"));
                item.put("purchaseCount", rs.getLong("purchase_count"));
                item.put("activeUsers", rs.getLong("active_users"));
                result.add(item);
            }
        } catch (Exception e) {
            log.error("Failed to get daily trend", e);
        }
        return result;
    }

    private Map<String, Object> getBreakdownBySource(String tenantId, String bizType, String startDate, String endDate) {
        Map<String, Object> result = new LinkedHashMap<>();

        String sql = "SELECT " +
                "source, " +
                "sum(if(event_type = 'add_to_cart', 1, 0)) as add_count, " +
                "sum(if(event_type = 'checkout_confirm', 1, 0)) as purchase_count, " +
                "count(distinct user_id) as user_count " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? AND source != '' ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        sql += "GROUP BY source ORDER BY add_count DESC";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", rs.getString("source"));
                item.put("addCount", rs.getLong("add_count"));
                item.put("purchaseCount", rs.getLong("purchase_count"));
                item.put("userCount", rs.getLong("user_count"));
                list.add(item);
            }
            result.put("list", list);
        } catch (Exception e) {
            log.error("Failed to get breakdown by source", e);
        }
        return result;
    }

    private Map<String, Object> getBreakdownByCategory(String tenantId, String bizType, String startDate, String endDate) {
        Map<String, Object> result = new LinkedHashMap<>();

        String sql = "SELECT " +
                "category_id, category_name, " +
                "sum(if(event_type = 'add_to_cart', 1, 0)) as add_count, " +
                "sum(if(event_type = 'checkout_confirm', 1, 0)) as purchase_count, " +
                "count(distinct user_id) as user_count " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? AND category_id != '' ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        sql += "GROUP BY category_id, category_name ORDER BY add_count DESC LIMIT 20";

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("categoryId", rs.getString("category_id"));
                item.put("categoryName", rs.getString("category_name"));
                item.put("addCount", rs.getLong("add_count"));
                item.put("purchaseCount", rs.getLong("purchase_count"));
                item.put("userCount", rs.getLong("user_count"));
                list.add(item);
            }
            result.put("list", list);
        } catch (Exception e) {
            log.error("Failed to get breakdown by category", e);
        }
        return result;
    }

    public Map<String, Object> drillDown(AnalyticsQueryDTO query) {
        String tenantId = StringUtils.defaultIfBlank(query.getTenantId(), CartContextHolder.getTenantId());
        String bizType = resolveBizType(query.getBizType());
        String startDate = StringUtils.defaultIfBlank(query.getStartDate(),
                LocalDate.now().minusDays(30).format(DATE_FMT));
        String endDate = StringUtils.defaultIfBlank(query.getEndDate(),
                LocalDate.now().format(DATE_FMT));
        String dimension = StringUtils.defaultIfBlank(query.getDimension(), "biz_type");
        int topN = query.getTopN() != null ? query.getTopN() : 20;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("startDate", startDate);
        result.put("endDate", endDate);

        String dimColumn = mapDimensionToColumn(dimension);
        if (dimColumn == null) {
            result.put("error", "Invalid dimension: " + dimension);
            return result;
        }

        String sql = "SELECT " +
                dimColumn + " as dim_value, " +
                "sum(if(event_type = 'add_to_cart', 1, 0)) as add_count, " +
                "sum(if(event_type = 'remove_from_cart', 1, 0)) as remove_count, " +
                "sum(if(event_type = 'checkout_create', 1, 0)) as checkout_count, " +
                "sum(if(event_type = 'checkout_confirm', 1, 0)) as purchase_count, " +
                "count(distinct user_id) as user_count, " +
                "sum(if(event_type = 'add_to_cart', unit_price * quantity, 0)) as add_amount " +
                "FROM " + TABLE_CART_EVENTS + " " +
                "WHERE tenant_id = ? AND date >= ? AND date <= ? AND " + dimColumn + " != '' ";

        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(startDate);
        params.add(endDate);

        if (!"all".equalsIgnoreCase(bizType) && !"biz_type".equals(dimension)) {
            sql += "AND biz_type = ? ";
            params.add(bizType);
        }

        if (StringUtils.isNotBlank(query.getCategoryId()) && !"category".equals(dimension)) {
            sql += "AND category_id = ? ";
            params.add(query.getCategoryId());
        }

        if (StringUtils.isNotBlank(query.getSource()) && !"source".equals(dimension)) {
            sql += "AND source = ? ";
            params.add(query.getSource());
        }

        sql += "GROUP BY " + dimColumn + " ORDER BY add_count DESC LIMIT ?";
        params.add(topN);

        try (Connection conn = clickHouseDataSource.getConnection();
             PreparedStatement pstmt = buildPreparedStatement(conn, sql, params);
             ResultSet rs = pstmt.executeQuery()) {

            List<Map<String, Object>> list = new ArrayList<>();
            while (rs.next()) {
                long addCount = rs.getLong("add_count");
                long purchaseCount = rs.getLong("purchase_count");

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("dimValue", rs.getString("dim_value"));
                item.put("addCount", addCount);
                item.put("removeCount", rs.getLong("remove_count"));
                item.put("checkoutCount", rs.getLong("checkout_count"));
                item.put("purchaseCount", purchaseCount);
                item.put("userCount", rs.getLong("user_count"));
                item.put("addAmount", rs.getBigDecimal("add_amount"));
                item.put("conversionRate", addCount > 0 ?
                        BigDecimal.valueOf(purchaseCount * 100.0 / addCount).setScale(2, RoundingMode.HALF_UP) :
                        BigDecimal.ZERO);
                list.add(item);
            }
            result.put("data", list);
            result.put("total", list.size());
        } catch (Exception e) {
            log.error("Failed to drill down by dimension: {}", dimension, e);
            result.put("error", e.getMessage());
        }
        return result;
    }

    private String mapDimensionToColumn(String dimension) {
        switch (dimension) {
            case "biz_type":
                return "biz_type";
            case "category":
                return "category_id";
            case "source":
                return "source";
            case "product":
                return "sku_id";
            case "hour":
                return "hour";
            default:
                return null;
        }
    }

    private String resolveBizType(String bizType) {
        return StringUtils.defaultIfBlank(bizType, "all");
    }

    private PreparedStatement buildPreparedStatement(Connection conn, String sql, List<Object> params) throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement(sql);
        for (int i = 0; i < params.size(); i++) {
            pstmt.setObject(i + 1, params.get(i));
        }
        return pstmt;
    }
}
