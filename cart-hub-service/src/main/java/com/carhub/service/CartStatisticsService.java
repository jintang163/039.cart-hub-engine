package com.carhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.context.CartContextHolder;
import com.carhub.domain.entity.CartStatisticsEntity;
import com.carhub.mapper.CartStatisticsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
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

    @Resource
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public Map<String, Object> getOverview(String bizType, String startDate, String endDate) {
        String tenantId = CartContextHolder.getTenantId();
        bizType = resolveBizType(bizType);

        LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
        LocalDate end = parseDate(endDate, LocalDate.now());

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
            totalItemCount += s.getTotalItemCount() != null ? s.getTotalItemCount() : 0;
            totalAddCount += s.getTotalAddCount() != null ? s.getTotalAddCount() : 0;
            totalDeleteCount += s.getTotalDeleteCount() != null ? s.getTotalDeleteCount() : 0;
            totalUpdateCount += s.getTotalUpdateCount() != null ? s.getTotalUpdateCount() : 0;
            totalClearCount += s.getTotalClearCount() != null ? s.getTotalClearCount() : 0;
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
        result.put("avgCartSize", totalAvgSize.divide(BigDecimal.valueOf(days), 2, BigDecimal.ROUND_HALF_UP));
        result.put("avgCartAmount", totalAvgAmount.divide(BigDecimal.valueOf(days), 2, BigDecimal.ROUND_HALF_UP));
        return result;
    }

    public List<Map<String, Object>> getDailyStatistics(String bizType, String startDate, String endDate) {
        String tenantId = CartContextHolder.getTenantId();
        bizType = resolveBizType(bizType);

        LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
        LocalDate end = parseDate(endDate, LocalDate.now());

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
        return result;
    }

    public List<Map<String, Object>> getBizComparison(String startDate, String endDate) {
        String tenantId = CartContextHolder.getTenantId();
        LocalDate start = parseDate(startDate, LocalDate.now().minusDays(30));
        LocalDate end = parseDate(endDate, LocalDate.now());

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
            long ops = (s.getTotalAddCount() != null ? s.getTotalAddCount() : 0)
                    + (s.getTotalDeleteCount() != null ? s.getTotalDeleteCount() : 0)
                    + (s.getTotalUpdateCount() != null ? s.getTotalUpdateCount() : 0)
                    + (s.getTotalClearCount() != null ? s.getTotalClearCount() : 0);
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

        result.put("tenantId", tenantId);
        result.put("bizType", bizType);
        result.put("activeCartUsers", activeUsers.get());
        result.put("totalCartItems", totalItems.get());
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
