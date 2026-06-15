package com.carhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.domain.entity.CartStatisticsEntity;
import com.carhub.mapper.CartStatisticsMapper;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RHyperLogLog;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartStatisticsService {

    private final CartStatisticsMapper cartStatisticsMapper;
    private final RedissonClient redissonClient;
    private final CartRedisStorage cartRedisStorage;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String STAT_COUNTER_PREFIX = "cart:stat:counter:";
    private static final String STAT_HLL_PREFIX = "cart:stat:hll:";
    private static final String STAT_ITEM_AMOUNT_PREFIX = "cart:stat:amount:";

    private static final String METRIC_ADD_COUNT = "add_count";
    private static final String METRIC_DELETE_COUNT = "delete_count";
    private static final String METRIC_UPDATE_COUNT = "update_count";
    private static final String METRIC_CLEAR_COUNT = "clear_count";
    private static final String METRIC_ITEM_COUNT = "item_count";
    private static final String METRIC_SHARE_COUNT = "share_count";
    private static final String METRIC_SNAPSHOT_COUNT = "snapshot_count";
    private static final String METRIC_ACTIVE_USERS = "active_users";
    private static final String METRIC_ADD_USERS = "add_users";
    private static final String METRIC_NEW_USERS = "new_users";

    /**
     * 记录加购操作
     */
    public void recordAdd(String tenantId, String bizType, String userId, int itemCount, BigDecimal amount) {
        if (StringUtils.isAnyBlank(tenantId, bizType, userId)) return;
        String date = todayStr();
        incrCounter(tenantId, bizType, date, METRIC_ADD_COUNT, 1);
        incrCounter(tenantId, bizType, date, METRIC_ITEM_COUNT, itemCount);
        incrAmount(tenantId, bizType, date, amount);
        pfadd(tenantId, bizType, date, METRIC_ACTIVE_USERS, userId);
        pfadd(tenantId, bizType, date, METRIC_ADD_USERS, userId);
        checkNewUser(tenantId, bizType, userId, date);
    }

    /**
     * 记录删除操作
     */
    public void recordDelete(String tenantId, String bizType, String userId) {
        if (StringUtils.isAnyBlank(tenantId, bizType, userId)) return;
        String date = todayStr();
        incrCounter(tenantId, bizType, date, METRIC_DELETE_COUNT, 1);
        pfadd(tenantId, bizType, date, METRIC_ACTIVE_USERS, userId);
    }

    /**
     * 记录修改操作
     */
    public void recordUpdate(String tenantId, String bizType, String userId) {
        if (StringUtils.isAnyBlank(tenantId, bizType, userId)) return;
        String date = todayStr();
        incrCounter(tenantId, bizType, date, METRIC_UPDATE_COUNT, 1);
        pfadd(tenantId, bizType, date, METRIC_ACTIVE_USERS, userId);
    }

    /**
     * 记录清空操作
     */
    public void recordClear(String tenantId, String bizType, String userId) {
        if (StringUtils.isAnyBlank(tenantId, bizType, userId)) return;
        String date = todayStr();
        incrCounter(tenantId, bizType, date, METRIC_CLEAR_COUNT, 1);
        pfadd(tenantId, bizType, date, METRIC_ACTIVE_USERS, userId);
    }

    /**
     * 记录分享
     */
    public void recordShare(String tenantId, String bizType, String userId) {
        if (StringUtils.isAnyBlank(tenantId, bizType, userId)) return;
        String date = todayStr();
        incrCounter(tenantId, bizType, date, METRIC_SHARE_COUNT, 1);
        pfadd(tenantId, bizType, date, METRIC_ACTIVE_USERS, userId);
    }

    /**
     * 记录快照
     */
    public void recordSnapshot(String tenantId, String bizType, String userId) {
        if (StringUtils.isAnyBlank(tenantId, bizType, userId)) return;
        String date = todayStr();
        incrCounter(tenantId, bizType, date, METRIC_SNAPSHOT_COUNT, 1);
        pfadd(tenantId, bizType, date, METRIC_ACTIVE_USERS, userId);
    }

    /**
     * 定时任务：每天凌晨2点15分，把前一天的统计数据聚合写入MySQL
     */
    @Scheduled(cron = "0 15 2 * * ?")
    public void aggregateDailyStatistics() {
        String yesterday = LocalDate.now().minusDays(1).format(DATE_FMT);
        log.info("开始聚合昨日统计数据: date={}", yesterday);
        try {
            Set<String> tenantBizPairs = collectTenantBizPairs(yesterday);
            int success = 0;
            for (String pair : tenantBizPairs) {
                try {
                    String[] parts = pair.split(":");
                    if (parts.length >= 2) {
                        aggregateSingle(parts[0], parts[1], yesterday);
                        success++;
                    }
                } catch (Exception e) {
                    log.error("聚合统计失败: pair={}", pair, e);
                }
            }
            log.info("统计数据聚合完成: date={}, success={}", yesterday, success);
        } catch (Exception e) {
            log.error("统计数据聚合异常", e);
        }
    }

    /**
     * 手动触发聚合某天的数据（管理后台用）
     */
    public void aggregateByDate(String date) {
        if (StringUtils.isBlank(date)) {
            date = LocalDate.now().minusDays(1).format(DATE_FMT);
        }
        Set<String> tenantBizPairs = collectTenantBizPairs(date);
        for (String pair : tenantBizPairs) {
            try {
                String[] parts = pair.split(":");
                if (parts.length >= 2) {
                    aggregateSingle(parts[0], parts[1], date);
                }
            } catch (Exception e) {
                log.error("聚合统计失败: pair={}", pair, e);
            }
        }
    }

    private void aggregateSingle(String tenantId, String bizType, String date) {
        LocalDate statDate = LocalDate.parse(date, DATE_FMT);
        CartStatisticsEntity stat = new CartStatisticsEntity();
        stat.setTenantId(tenantId);
        stat.setBizType(bizType);
        stat.setStatDate(statDate);

        int activeUserCount = (int) pfcount(tenantId, bizType, date, METRIC_ACTIVE_USERS);
        int addUserCount = (int) pfcount(tenantId, bizType, date, METRIC_ADD_USERS);
        int newUserCount = (int) pfcount(tenantId, bizType, date, METRIC_NEW_USERS);

        stat.setActiveUserCount(activeUserCount);
        stat.setAddUserCount(addUserCount);
        stat.setNewUserCount(newUserCount);
        stat.setTotalAddCount(getCounterInt(tenantId, bizType, date, METRIC_ADD_COUNT));
        stat.setTotalDeleteCount(getCounterInt(tenantId, bizType, date, METRIC_DELETE_COUNT));
        stat.setTotalUpdateCount(getCounterInt(tenantId, bizType, date, METRIC_UPDATE_COUNT));
        stat.setTotalClearCount(getCounterInt(tenantId, bizType, date, METRIC_CLEAR_COUNT));
        stat.setTotalItemCount(getCounterInt(tenantId, bizType, date, METRIC_ITEM_COUNT));
        stat.setShareCount(getCounterInt(tenantId, bizType, date, METRIC_SHARE_COUNT));
        stat.setSnapshotCount(getCounterInt(tenantId, bizType, date, METRIC_SNAPSHOT_COUNT));

        BigDecimal totalAmount = getAmountTotal(tenantId, bizType, date);
        int addCount = stat.getTotalAddCount() != null ? stat.getTotalAddCount() : 0;
        stat.setAvgCartSize(addCount > 0
                ? BigDecimal.valueOf((double) stat.getTotalItemCount() / addCount).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        stat.setAvgCartAmount(addCount > 0
                ? totalAmount.divide(BigDecimal.valueOf(addCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);

        LambdaQueryWrapper<CartStatisticsEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartStatisticsEntity::getTenantId, tenantId)
                .eq(CartStatisticsEntity::getBizType, bizType)
                .eq(CartStatisticsEntity::getStatDate, statDate);
        CartStatisticsEntity exist = cartStatisticsMapper.selectOne(wrapper);

        if (exist != null) {
            stat.setId(exist.getId());
            cartStatisticsMapper.updateById(stat);
        } else {
            cartStatisticsMapper.insert(stat);
        }

        log.info("统计数据已入库: tenantId={}, bizType={}, date={}, activeUsers={}, addCount={}",
                tenantId, bizType, date, activeUserCount, stat.getTotalAddCount());
    }

    private Set<String> collectTenantBizPairs(String date) {
        Set<String> pairs = new HashSet<>();
        String pattern = STAT_COUNTER_PREFIX + "*:" + date + ":*";
        try {
            var keys = redissonClient.getKeys().getKeysByPattern(pattern, 1000);
            for (String key : keys) {
                String pair = extractTenantBiz(key, STAT_COUNTER_PREFIX, date);
                if (pair != null) {
                    pairs.add(pair);
                }
            }
        } catch (Exception e) {
            log.warn("collectTenantBizPairs error", e);
        }
        return pairs;
    }

    private String extractTenantBiz(String key, String prefix, String date) {
        try {
            String body = key.substring(prefix.length());
            int idx = body.indexOf(":" + date + ":");
            if (idx > 0) {
                return body.substring(0, idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String todayStr() {
        return LocalDate.now().format(DATE_FMT);
    }

    private String counterKey(String tenantId, String bizType, String date, String metric) {
        return STAT_COUNTER_PREFIX + tenantId + ":" + bizType + ":" + date + ":" + metric;
    }

    private String hllKey(String tenantId, String bizType, String date, String metric) {
        return STAT_HLL_PREFIX + tenantId + ":" + bizType + ":" + date + ":" + metric;
    }

    private String amountKey(String tenantId, String bizType, String date) {
        return STAT_ITEM_AMOUNT_PREFIX + tenantId + ":" + bizType + ":" + date;
    }

    private void incrCounter(String tenantId, String bizType, String date, String metric, long delta) {
        String key = counterKey(tenantId, bizType, date, metric);
        stringRedisTemplate.opsForValue().increment(key, delta);
        stringRedisTemplate.expire(key, java.time.Duration.ofDays(30));
    }

    private int getCounterInt(String tenantId, String bizType, String date, String metric) {
        String key = counterKey(tenantId, bizType, date, metric);
        String val = stringRedisTemplate.opsForValue().get(key);
        return val == null ? 0 : Integer.parseInt(val);
    }

    private void pfadd(String tenantId, String bizType, String date, String metric, String userId) {
        String key = hllKey(tenantId, bizType, date, metric);
        RHyperLogLog<String> hll = redissonClient.getHyperLogLog(key);
        hll.add(userId);
        hll.expire(java.util.concurrent.TimeUnit.DAYS, 30);
    }

    private long pfcount(String tenantId, String bizType, String date, String metric) {
        String key = hllKey(tenantId, bizType, date, metric);
        RHyperLogLog<String> hll = redissonClient.getHyperLogLog(key);
        return hll.count();
    }

    private void incrAmount(String tenantId, String bizType, String date, BigDecimal amount) {
        if (amount == null) return;
        String key = amountKey(tenantId, bizType, date);
        stringRedisTemplate.opsForValue().increment(key, amount.doubleValue());
        stringRedisTemplate.expire(key, java.time.Duration.ofDays(30));
    }

    private BigDecimal getAmountTotal(String tenantId, String bizType, String date) {
        String key = amountKey(tenantId, bizType, date);
        String val = stringRedisTemplate.opsForValue().get(key);
        if (val == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(val);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 检查是否新用户（第一次出现）
     */
    private void checkNewUser(String tenantId, String bizType, String userId, String date) {
        String firstKey = "cart:stat:first:" + tenantId + ":" + bizType + ":" + userId;
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(firstKey, date, java.time.Duration.ofDays(90));
        if (Boolean.TRUE.equals(first)) {
            pfadd(tenantId, bizType, date, METRIC_NEW_USERS, userId);
        }
    }

    // ============================================================
    // 以下是查询接口（MySQL为主，今日数据从Redis实时汇总）
    // ============================================================

    public Map<String, Object> getOverview(String bizType, String startDate, String endDate) {
        String tenantId = CartContextHolder.getTenantId();
        bizType = resolveBizType(bizType);

        LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
        LocalDate end = parseDate(endDate, LocalDate.now().minusDays(1));
        String today = LocalDate.now().format(DATE_FMT);

        LambdaQueryWrapper<CartStatisticsEntity> wrapper = buildWrapper(tenantId, bizType, start, end);
        List<CartStatisticsEntity> list = cartStatisticsMapper.selectList(wrapper);

        Map<String, Object> result = new HashMap<>();
        int totalActiveUsers = 0;
        int totalNewUsers = 0;
        int totalAddUsers = 0;
        long totalItemCount = 0;
        long totalAddCount = 0;
        long totalDeleteCount = 0;
        long totalUpdateCount = 0;
        long totalClearCount = 0;
        int totalShares = 0;
        int totalSnapshots = 0;
        BigDecimal totalAvgSize = BigDecimal.ZERO;
        BigDecimal totalAvgAmount = BigDecimal.ZERO;

        for (CartStatisticsEntity s : list) {
            totalActiveUsers += s.getActiveUserCount() != null ? s.getActiveUserCount() : 0;
            totalNewUsers += s.getNewUserCount() != null ? s.getNewUserCount() : 0;
            totalAddUsers += s.getAddUserCount() != null ? s.getAddUserCount() : 0;
            totalItemCount += s.getTotalItemCount() != null ? s.getTotalItemCount().longValue() : 0L;
            totalAddCount += s.getTotalAddCount() != null ? s.getTotalAddCount().longValue() : 0L;
            totalDeleteCount += s.getTotalDeleteCount() != null ? s.getTotalDeleteCount().longValue() : 0L;
            totalUpdateCount += s.getTotalUpdateCount() != null ? s.getTotalUpdateCount().longValue() : 0L;
            totalClearCount += s.getTotalClearCount() != null ? s.getTotalClearCount().longValue() : 0L;
            totalShares += s.getShareCount() != null ? s.getShareCount() : 0;
            totalSnapshots += s.getSnapshotCount() != null ? s.getSnapshotCount() : 0;
            if (s.getAvgCartSize() != null) {
                totalAvgSize = totalAvgSize.add(s.getAvgCartSize());
            }
            if (s.getAvgCartAmount() != null) {
                totalAvgAmount = totalAvgAmount.add(s.getAvgCartAmount());
            }
        }

        int days = list.size() > 0 ? list.size() : 1;
        result.put("tenantId", tenantId);
        result.put("bizType", bizType);
        result.put("startDate", start.format(DATE_FMT));
        result.put("endDate", end.format(DATE_FMT));
        result.put("days", list.size());
        result.put("totalActiveUsers", totalActiveUsers);
        result.put("totalNewUsers", totalNewUsers);
        result.put("totalAddUsers", totalAddUsers);
        result.put("totalItemCount", totalItemCount);
        result.put("totalAddCount", totalAddCount);
        result.put("totalDeleteCount", totalDeleteCount);
        result.put("totalUpdateCount", totalUpdateCount);
        result.put("totalClearCount", totalClearCount);
        result.put("totalShares", totalShares);
        result.put("totalSnapshots", totalSnapshots);
        result.put("avgCartSize", totalAvgSize.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP));
        result.put("avgCartAmount", totalAvgAmount.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP));
        return result;
    }

    public List<Map<String, Object>> getDailyStatistics(String bizType, String startDate, String endDate) {
        String tenantId = CartContextHolder.getTenantId();
        bizType = resolveBizType(bizType);

        LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
        LocalDate end = parseDate(endDate, LocalDate.now().minusDays(1));

        LambdaQueryWrapper<CartStatisticsEntity> wrapper = buildWrapper(tenantId, bizType, start, end);
        wrapper.orderByAsc(CartStatisticsEntity::getStatDate);
        List<CartStatisticsEntity> list = cartStatisticsMapper.selectList(wrapper);

        List<Map<String, Object>> result = new ArrayList<>();
        for (CartStatisticsEntity s : list) {
            Map<String, Object> item = new HashMap<>();
            item.put("statDate", s.getStatDate().format(DATE_FMT));
            item.put("activeUserCount", s.getActiveUserCount());
            item.put("newUserCount", s.getNewUserCount());
            item.put("addUserCount", s.getAddUserCount());
            item.put("totalItemCount", s.getTotalItemCount());
            item.put("totalAddCount", s.getTotalAddCount());
            item.put("totalDeleteCount", s.getTotalDeleteCount());
            item.put("totalUpdateCount", s.getTotalUpdateCount());
            item.put("totalClearCount", s.getTotalClearCount());
            item.put("avgCartSize", s.getAvgCartSize());
            item.put("avgCartAmount", s.getAvgCartAmount());
            item.put("shareCount", s.getShareCount());
            item.put("snapshotCount", s.getSnapshotCount());
            result.add(item);
        }

        String today = LocalDate.now().format(DATE_FMT);
        if (end.isEqual(LocalDate.now()) || end.isAfter(LocalDate.now().minusDays(1))) {
            Map<String, Object> todayMap = getTodayStatistics(tenantId, bizType, today);
            if (todayMap != null) {
                result.add(todayMap);
            }
        }

        return result;
    }

    private Map<String, Object> getTodayStatistics(String tenantId, String bizType, String date) {
        try {
            Map<String, Object> item = new HashMap<>();
            item.put("statDate", date);
            item.put("activeUserCount", (int) pfcount(tenantId, bizType, date, METRIC_ACTIVE_USERS));
            item.put("newUserCount", (int) pfcount(tenantId, bizType, date, METRIC_NEW_USERS));
            item.put("addUserCount", (int) pfcount(tenantId, bizType, date, METRIC_ADD_USERS));
            item.put("totalItemCount", (long) getCounterInt(tenantId, bizType, date, METRIC_ITEM_COUNT));
            item.put("totalAddCount", (long) getCounterInt(tenantId, bizType, date, METRIC_ADD_COUNT));
            item.put("totalDeleteCount", (long) getCounterInt(tenantId, bizType, date, METRIC_DELETE_COUNT));
            item.put("totalUpdateCount", (long) getCounterInt(tenantId, bizType, date, METRIC_UPDATE_COUNT));
            item.put("totalClearCount", (long) getCounterInt(tenantId, bizType, date, METRIC_CLEAR_COUNT));
            item.put("shareCount", getCounterInt(tenantId, bizType, date, METRIC_SHARE_COUNT));
            item.put("snapshotCount", getCounterInt(tenantId, bizType, date, METRIC_SNAPSHOT_COUNT));
            return item;
        } catch (Exception e) {
            log.warn("getTodayStatistics error", e);
            return null;
        }
    }

    public List<Map<String, Object>> getBizComparison(String startDate, String endDate) {
        String tenantId = CartContextHolder.getTenantId();
        LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
        LocalDate end = parseDate(endDate, LocalDate.now().minusDays(1));

        LambdaQueryWrapper<CartStatisticsEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartStatisticsEntity::getTenantId, tenantId)
                .between(CartStatisticsEntity::getStatDate, start, end);
        List<CartStatisticsEntity> list = cartStatisticsMapper.selectList(wrapper);

        Map<String, Map<String, Object>> bizMap = new LinkedHashMap<>();
        for (CartStatisticsEntity s : list) {
            String key = s.getBizType();
            Map<String, Object> bizData = bizMap.computeIfAbsent(key, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("bizType", k);
                m.put("activeUserCount", 0);
                m.put("totalItemCount", 0L);
                m.put("totalAddCount", 0L);
                m.put("totalOperationCount", 0L);
                m.put("shareCount", 0);
                m.put("snapshotCount", 0);
                return m;
            });
            bizData.put("activeUserCount",
                    ((Integer) bizData.get("activeUserCount")) + (s.getActiveUserCount() != null ? s.getActiveUserCount() : 0));
            bizData.put("totalItemCount",
                    ((Long) bizData.get("totalItemCount")) + (s.getTotalItemCount() != null ? s.getTotalItemCount().longValue() : 0L));
            bizData.put("totalAddCount",
                    ((Long) bizData.get("totalAddCount")) + (s.getTotalAddCount() != null ? s.getTotalAddCount().longValue() : 0L));
            long ops = (s.getTotalAddCount() != null ? s.getTotalAddCount().longValue() : 0L)
                    + (s.getTotalDeleteCount() != null ? s.getTotalDeleteCount().longValue() : 0L)
                    + (s.getTotalUpdateCount() != null ? s.getTotalUpdateCount().longValue() : 0L)
                    + (s.getTotalClearCount() != null ? s.getTotalClearCount().longValue() : 0L);
            bizData.put("totalOperationCount", ((Long) bizData.get("totalOperationCount")) + ops);
            bizData.put("shareCount",
                    ((Integer) bizData.get("shareCount")) + (s.getShareCount() != null ? s.getShareCount() : 0));
            bizData.put("snapshotCount",
                    ((Integer) bizData.get("snapshotCount")) + (s.getSnapshotCount() != null ? s.getSnapshotCount() : 0));
        }
        return new ArrayList<>(bizMap.values());
    }

    public Map<String, Object> getRealtimeStatistics(String bizType) {
        String tenantId = CartContextHolder.getTenantId();
        bizType = resolveBizType(bizType);

        Map<String, Object> result = new HashMap<>();
        AtomicInteger activeUsers = new AtomicInteger(0);
        AtomicInteger totalItems = new AtomicInteger(0);

        String pattern = RedisKeyConstant.buildCartKey(tenantId, bizType, "*");
        try {
            var keys = redissonClient.getKeys().getKeysByPattern(pattern, 1000);
            for (String key : keys) {
                try {
                    var map = redissonClient.getMap(key);
                    int size = map.size();
                    if (size > 0) {
                        activeUsers.incrementAndGet();
                        totalItems.addAndGet(size);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.warn("getRealtimeStatistics scan keys error", e);
        }

        String today = LocalDate.now().format(DATE_FMT);
        result.put("tenantId", tenantId);
        result.put("bizType", bizType);
        result.put("activeCartUsers", activeUsers.get());
        result.put("totalCartItems", totalItems.get());
        result.put("todayActiveUsers", (int) pfcount(tenantId, bizType, today, METRIC_ACTIVE_USERS));
        result.put("todayAddCount", getCounterInt(tenantId, bizType, today, METRIC_ADD_COUNT));
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    private LambdaQueryWrapper<CartStatisticsEntity> buildWrapper(String tenantId, String bizType,
                                                                   LocalDate start, LocalDate end) {
        LambdaQueryWrapper<CartStatisticsEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartStatisticsEntity::getTenantId, tenantId);
        if (!"all".equalsIgnoreCase(bizType)) {
            wrapper.eq(CartStatisticsEntity::getBizType, bizType);
        }
        wrapper.between(CartStatisticsEntity::getStatDate, start, end);
        return wrapper;
    }

    private LocalDate parseDate(String dateStr, LocalDate defaultValue) {
        if (StringUtils.isBlank(dateStr)) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(dateStr, DATE_FMT);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String resolveBizType(String bizType) {
        return StringUtils.defaultIfBlank(bizType, "all");
    }

}
