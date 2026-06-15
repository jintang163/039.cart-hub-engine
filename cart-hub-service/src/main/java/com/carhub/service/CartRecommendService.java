package com.carhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.entity.CartSnapshotEntity;
import com.carhub.domain.entity.SkuAssociationEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.RecommendItemVO;
import com.carhub.mapper.CartSnapshotMapper;
import com.carhub.mapper.SkuAssociationMapper;
import com.carhub.storage.CartRedisStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
class CartRecommendService {

    private final CartHubProperties cartHubProperties;
    private final SkuAssociationMapper skuAssociationMapper;
    private final CartSnapshotMapper cartSnapshotMapper;
    private final CartRedisStorage cartRedisStorage;
    private final StringRedisTemplate stringRedisTemplate;

    @Resource
    private RestTemplate restTemplate;

    public List<RecommendItemVO> getRecommendations(String tenantId, String bizType, String userId,
                                                     List<String> currentSkus, Integer topN) {
        if (!cartHubProperties.getRecommend().getEnable()) {
            return Collections.emptyList();
        }

        if (topN == null || topN <= 0) {
            topN = cartHubProperties.getRecommend().getTopN();
        }

        String cacheKey = RedisKeyConstant.buildRecommendKey(tenantId, bizType, userId);
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.isNotBlank(cached)) {
                List<RecommendItemVO> cachedList = JsonUtil.fromJsonList(cached, RecommendItemVO.class);
                if (cachedList != null && !cachedList.isEmpty()) {
                    if (currentSkus != null && !currentSkus.isEmpty()) {
                        cachedList = cachedList.stream()
                                .filter(r -> !currentSkus.contains(r.getSkuId()))
                                .collect(Collectors.toList());
                    }
                    return cachedList.stream().limit(topN).collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("Read recommend cache failed: {}", e.getMessage());
        }

        List<RecommendItemVO> results = tryExternalRecommend(tenantId, bizType, userId, currentSkus, topN);
        if (results != null && !results.isEmpty()) {
            cacheRecommendations(cacheKey, results);
            return results;
        }

        results = collaborativeFilterRecommend(tenantId, bizType, userId, currentSkus, topN);
        if (results != null && !results.isEmpty()) {
            cacheRecommendations(cacheKey, results);
            return results;
        }

        return Collections.emptyList();
    }

    private List<RecommendItemVO> tryExternalRecommend(String tenantId, String bizType, String userId,
                                                        List<String> currentSkus, Integer topN) {
        String recommendUrl = cartHubProperties.getRecommend().getRecommendUrl();
        if (StringUtils.isBlank(recommendUrl)) {
            return null;
        }

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenantId);
            body.put("bizType", bizType);
            body.put("userId", userId);
            body.put("currentSkus", currentSkus);
            body.put("topN", topN);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    recommendUrl, HttpMethod.POST, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return JsonUtil.fromJsonList(response.getBody(), RecommendItemVO.class);
            }
        } catch (Exception e) {
            log.warn("Call external recommend API failed, fallback to local: {}", e.getMessage());
        }
        return null;
    }

    private List<RecommendItemVO> collaborativeFilterRecommend(String tenantId, String bizType, String userId,
                                                                 List<String> currentSkus, Integer topN) {
        if (currentSkus == null || currentSkus.isEmpty()) {
            Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
            if (cart != null && cart.getItems() != null) {
                currentSkus = cart.getItems().stream()
                        .map(CartItem::getSkuId)
                        .collect(Collectors.toList());
            }
        }

        if (currentSkus == null || currentSkus.isEmpty()) {
            return recommendByUserHistory(tenantId, bizType, userId, topN);
        }

        return recommendBySkuAssociation(tenantId, bizType, currentSkus, topN);
    }

    private List<RecommendItemVO> recommendBySkuAssociation(String tenantId, String bizType,
                                                              List<String> currentSkus, Integer topN) {
        Double minConfidence = cartHubProperties.getRecommend().getMinConfidence();
        Double minSupport = cartHubProperties.getRecommend().getMinSupport();

        LambdaQueryWrapper<SkuAssociationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkuAssociationEntity::getTenantId, tenantId)
                .eq(SkuAssociationEntity::getBizType, bizType)
                .in(SkuAssociationEntity::getSourceSkuId, currentSkus)
                .ge(SkuAssociationEntity::getConfidence, minConfidence)
                .ge(SkuAssociationEntity::getSupport, minSupport)
                .eq(SkuAssociationEntity::getStatus, 1)
                .eq(SkuAssociationEntity::getDeleted, 0)
                .orderByDesc(SkuAssociationEntity::getLift);

        List<SkuAssociationEntity> associations = skuAssociationMapper.selectList(wrapper);

        Map<String, RecommendItemVO> recommendMap = new LinkedHashMap<>();
        for (SkuAssociationEntity assoc : associations) {
            String targetSku = assoc.getTargetSkuId();
            if (currentSkus.contains(targetSku)) {
                continue;
            }

            RecommendItemVO existing = recommendMap.get(targetSku);
            double newScore = assoc.getLift() * assoc.getConfidence();
            if (existing == null || newScore > existing.getScore()) {
                List<String> sourceSkus = new ArrayList<>();
                sourceSkus.add(assoc.getSourceSkuId());
                if (existing != null && existing.getSourceSkus() != null) {
                    sourceSkus.addAll(existing.getSourceSkus());
                }

                String reason = buildRecommendReason(assoc);
                recommendMap.put(targetSku, RecommendItemVO.builder()
                        .skuId(targetSku)
                        .score(newScore)
                        .coOccurrenceCount(assoc.getCoOccurrenceCount())
                        .support(assoc.getSupport())
                        .confidence(assoc.getConfidence())
                        .lift(assoc.getLift())
                        .recommendReason(reason)
                        .sourceSkus(sourceSkus.stream().distinct().collect(Collectors.toList()))
                        .build());
            }
        }

        mergeRealtimeCoOccurrence(tenantId, bizType, currentSkus, recommendMap);

        Map<String, CartItem> itemInfoMap = buildSkuInfoMap(tenantId, bizType);
        List<RecommendItemVO> resultList = recommendMap.values().stream()
                .sorted(Comparator.comparingDouble(RecommendItemVO::getScore).reversed())
                .limit(topN)
                .collect(Collectors.toList());

        enrichRecommendItems(resultList, itemInfoMap);

        return resultList;
    }

    private void mergeRealtimeCoOccurrence(String tenantId, String bizType,
                                            List<String> currentSkus,
                                            Map<String, RecommendItemVO> recommendMap) {
        try {
            String coKey = RedisKeyConstant.buildSkuCoOccurrenceKey(tenantId, bizType);
            Map<Object, Object> coHash = stringRedisTemplate.opsForHash().entries(coKey);
            if (coHash == null || coHash.isEmpty()) {
                return;
            }

            Map<String, Integer> skuTotalRealtime = new HashMap<>();
            for (Map.Entry<Object, Object> entry : coHash.entrySet()) {
                String pairKey = (String) entry.getKey();
                String[] parts = pairKey.split(":");
                if (parts.length != 2) continue;
                int count = Integer.parseInt((String) entry.getValue());
                skuTotalRealtime.merge(parts[0], count, Integer::sum);
                skuTotalRealtime.merge(parts[1], count, Integer::sum);
            }

            int totalRealtime = skuTotalRealtime.values().stream().mapToInt(Integer::intValue).sum() / 2;
            if (totalRealtime <= 0) return;

            for (String sourceSku : currentSkus) {
                for (Map.Entry<Object, Object> entry : coHash.entrySet()) {
                    String pairKey = (String) entry.getKey();
                    String[] parts = pairKey.split(":");
                    if (parts.length != 2) continue;

                    String targetSku = null;
                    if (parts[0].equals(sourceSku)) {
                        targetSku = parts[1];
                    } else if (parts[1].equals(sourceSku)) {
                        targetSku = parts[0];
                    }
                    if (targetSku == null || currentSkus.contains(targetSku)) continue;

                    int coCount = Integer.parseInt((String) entry.getValue());
                    int sourceCount = skuTotalRealtime.getOrDefault(sourceSku, 1);
                    int targetCount = skuTotalRealtime.getOrDefault(targetSku, 1);

                    double support = (double) coCount / totalRealtime;
                    double confidence = (double) coCount / sourceCount;
                    double expectedProb = (double) targetCount / totalRealtime;
                    double lift = expectedProb > 0 ? confidence / expectedProb : 0.0;

                    double realtimeScore = lift * confidence * 0.5;

                    RecommendItemVO existing = recommendMap.get(targetSku);
                    if (existing == null) {
                        if (confidence >= cartHubProperties.getRecommend().getMinConfidence()
                                && support >= cartHubProperties.getRecommend().getMinSupport()) {
                            List<String> sourceSkus = new ArrayList<>();
                            sourceSkus.add(sourceSku);
                            recommendMap.put(targetSku, RecommendItemVO.builder()
                                    .skuId(targetSku)
                                    .score(realtimeScore)
                                    .coOccurrenceCount(coCount)
                                    .support(support)
                                    .confidence(confidence)
                                    .lift(lift)
                                    .recommendReason("最近常一起购买")
                                    .sourceSkus(sourceSkus)
                                    .build());
                        }
                    } else {
                        double boostFactor = 1.0 + (coCount / 100.0);
                        if (boostFactor > 1.5) boostFactor = 1.5;
                        double boostedScore = existing.getScore() * boostFactor;
                        existing.setScore(boostedScore);
                        if (existing.getCoOccurrenceCount() != null) {
                            existing.setCoOccurrenceCount(existing.getCoOccurrenceCount() + coCount);
                        }
                        List<String> sourceSkus = new ArrayList<>();
                        if (existing.getSourceSkus() != null) {
                            sourceSkus.addAll(existing.getSourceSkus());
                        }
                        sourceSkus.add(sourceSku);
                        existing.setSourceSkus(sourceSkus.stream().distinct().collect(Collectors.toList()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Merge realtime co-occurrence failed: {}", e.getMessage());
        }
    }

    private Map<String, CartItem> buildSkuInfoMap(String tenantId, String bizType) {
        Map<String, CartItem> infoMap = new HashMap<>();
        try {
            Cart cart = cartRedisStorage.getCart(tenantId, bizType, null);
            if (cart != null && cart.getItems() != null) {
                for (CartItem item : cart.getItems()) {
                    infoMap.put(item.getSkuId(), item);
                }
            }
        } catch (Exception e) {
            log.warn("Build sku info map from current cart failed: {}", e.getMessage());
        }
        try {
            LambdaQueryWrapper<CartSnapshotEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(CartSnapshotEntity::getTenantId, tenantId)
                    .eq(CartSnapshotEntity::getBizType, bizType)
                    .eq(CartSnapshotEntity::getDeleted, 0)
                    .orderByDesc(CartSnapshotEntity::getCreateTime)
                    .last("LIMIT 20");
            List<CartSnapshotEntity> snapshots = cartSnapshotMapper.selectList(wrapper);
            if (snapshots != null) {
                for (CartSnapshotEntity snapshot : snapshots) {
                    if (StringUtils.isBlank(snapshot.getCartSnapshot())) continue;
                    try {
                        Cart snapCart = JsonUtil.fromJson(snapshot.getCartSnapshot(), Cart.class);
                        if (snapCart != null && snapCart.getItems() != null) {
                            for (CartItem item : snapCart.getItems()) {
                                if (!infoMap.containsKey(item.getSkuId())) {
                                    infoMap.put(item.getSkuId(), item);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Build sku info map from snapshots failed: {}", e.getMessage());
        }
        return infoMap;
    }

    private void enrichRecommendItems(List<RecommendItemVO> items, Map<String, CartItem> infoMap) {
        if (items == null || items.isEmpty() || infoMap == null || infoMap.isEmpty()) {
            return;
        }
        for (RecommendItemVO item : items) {
            CartItem info = infoMap.get(item.getSkuId());
            if (info == null) continue;
            if (StringUtils.isBlank(item.getItemName()) && StringUtils.isNotBlank(info.getItemName())) {
                item.setItemName(info.getItemName());
            }
            if (StringUtils.isBlank(item.getItemImage()) && StringUtils.isNotBlank(info.getItemImage())) {
                item.setItemImage(info.getItemImage());
            }
            if ((item.getItemSpec() == null || item.getItemSpec().isEmpty())
                    && info.getItemSpec() != null && !info.getItemSpec().isEmpty()) {
                item.setItemSpec(info.getItemSpec());
            }
            if (item.getUnitPrice() == null && info.getUnitPrice() != null) {
                item.setUnitPrice(info.getUnitPrice());
            }
            if (item.getOriginalPrice() == null && info.getOriginalPrice() != null) {
                item.setOriginalPrice(info.getOriginalPrice());
            }
            if (StringUtils.isBlank(item.getSpuId()) && StringUtils.isNotBlank(info.getSpuId())) {
                item.setSpuId(info.getSpuId());
            }
        }
    }

    private List<RecommendItemVO> recommendByUserHistory(String tenantId, String bizType,
                                                          String userId, Integer topN) {
        Map<String, Integer> skuFreq = new HashMap<>();
        try {
            LambdaQueryWrapper<CartSnapshotEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(CartSnapshotEntity::getTenantId, tenantId)
                    .eq(CartSnapshotEntity::getBizType, bizType)
                    .eq(CartSnapshotEntity::getUserId, userId)
                    .eq(CartSnapshotEntity::getDeleted, 0)
                    .orderByDesc(CartSnapshotEntity::getCreateTime)
                    .last("LIMIT 20");
            List<CartSnapshotEntity> snapshots = cartSnapshotMapper.selectList(wrapper);
            if (snapshots != null) {
                for (CartSnapshotEntity snapshot : snapshots) {
                    if (StringUtils.isBlank(snapshot.getCartSnapshot())) continue;
                    try {
                        Cart snapCart = JsonUtil.fromJson(snapshot.getCartSnapshot(), Cart.class);
                        if (snapCart != null && snapCart.getItems() != null) {
                            for (CartItem item : snapCart.getItems()) {
                                skuFreq.merge(item.getSkuId(), 1, Integer::sum);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Get user history for recommend failed: {}", e.getMessage());
        }

        Map<String, CartItem> itemInfoMap = buildSkuInfoMap(tenantId, bizType);

        List<RecommendItemVO> result = skuFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(e -> RecommendItemVO.builder()
                        .skuId(e.getKey())
                        .score((double) e.getValue())
                        .recommendReason("根据您的购物历史推荐")
                        .coOccurrenceCount(e.getValue())
                        .build())
                .collect(Collectors.toList());

        enrichRecommendItems(result, itemInfoMap);
        return result;
    }

    private String buildRecommendReason(SkuAssociationEntity assoc) {
        if (assoc.getCoOccurrenceCount() != null && assoc.getCoOccurrenceCount() > 10) {
            return "与您购物车中商品常一起购买";
        } else if (assoc.getLift() != null && assoc.getLift() > 2.0) {
            return "购买了此商品的顾客也购买了";
        } else {
            return "猜你喜欢";
        }
    }

    private void cacheRecommendations(String cacheKey, List<RecommendItemVO> results) {
        try {
            int cacheSeconds = cartHubProperties.getRecommend().getCacheSeconds();
            stringRedisTemplate.opsForValue().set(cacheKey, JsonUtil.toJson(results),
                    Duration.ofSeconds(cacheSeconds));
        } catch (Exception e) {
            log.warn("Cache recommendations failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 */6 * * ?")
    public void scheduledAnalyzeAssociations() {
        if (!cartHubProperties.getRecommend().getEnable()) {
            return;
        }

        String lockKey = RedisKeyConstant.buildRecommendLockKey("global", "all");
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofHours(1));
        if (!Boolean.TRUE.equals(locked)) {
            log.info("Association analysis already running, skip");
            return;
        }

        try {
            analyzeAllAssociations();
        } catch (Exception e) {
            log.error("Scheduled association analysis failed", e);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    public void analyzeAllAssociations() {
        log.info("Starting SKU association analysis based on cart snapshots...");
        int recentDays = cartHubProperties.getRecommend().getAnalyzeRecentDays();
        int maxRecords = cartHubProperties.getRecommend().getMaxHistoryRecords();
        LocalDateTime startTime = LocalDateTime.now().minusDays(recentDays);
        LocalDateTime endTime = LocalDateTime.now();

        LambdaQueryWrapper<CartSnapshotEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(CartSnapshotEntity::getCreateTime, startTime)
                .eq(CartSnapshotEntity::getDeleted, 0)
                .orderByDesc(CartSnapshotEntity::getCreateTime)
                .last("LIMIT " + maxRecords);

        List<CartSnapshotEntity> snapshots = cartSnapshotMapper.selectList(wrapper);
        if (snapshots == null || snapshots.isEmpty()) {
            log.info("No snapshot data for association analysis");
            return;
        }

        Map<String, Set<String>> tenantBizKeyMap = new HashMap<>();
        Map<String, Map<String, Set<String>>> snapshotSkusByTenant = new HashMap<>();

        for (CartSnapshotEntity snapshot : snapshots) {
            if (StringUtils.isAnyBlank(snapshot.getTenantId(), snapshot.getBizType())) {
                continue;
            }
            if (StringUtils.isBlank(snapshot.getCartSnapshot())) {
                continue;
            }
            try {
                Cart cart = JsonUtil.fromJson(snapshot.getCartSnapshot(), Cart.class);
                if (cart == null || cart.getItems() == null || cart.getItems().size() < 2) {
                    continue;
                }

                String tenantBizKey = snapshot.getTenantId() + ":" + snapshot.getBizType();
                tenantBizKeyMap.computeIfAbsent(tenantBizKey, k -> new HashSet<>())
                        .add(snapshot.getSnapshotId());

                Set<String> skuSet = cart.getItems().stream()
                        .map(CartItem::getSkuId)
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.toSet());

                if (skuSet.size() >= 2) {
                    snapshotSkusByTenant.computeIfAbsent(tenantBizKey, k -> new HashMap<>())
                            .put(snapshot.getSnapshotId(), skuSet);
                }
            } catch (Exception ignored) {
            }
        }

        Double minSupport = cartHubProperties.getRecommend().getMinSupport();
        Double minConfidence = cartHubProperties.getRecommend().getMinConfidence();
        int totalUpdated = 0;

        for (Map.Entry<String, Map<String, Set<String>>> tenantEntry : snapshotSkusByTenant.entrySet()) {
            String tenantBizKey = tenantEntry.getKey();
            String[] parts = tenantBizKey.split(":");
            if (parts.length != 2) continue;
            String tenantId = parts[0];
            String bizType = parts[1];

            Map<String, Set<String>> snapshotSkus = tenantEntry.getValue();
            int totalTransactions = snapshotSkus.size();

            Map<String, Map<String, AtomicInteger>> coOccurrence = new HashMap<>();
            Map<String, AtomicInteger> skuTotalCount = new HashMap<>();

            for (Set<String> skus : snapshotSkus.values()) {
                for (String sku : skus) {
                    skuTotalCount.computeIfAbsent(sku, k -> new AtomicInteger(0)).incrementAndGet();
                }

                List<String> skuList = new ArrayList<>(skus);
                for (int i = 0; i < skuList.size(); i++) {
                    for (int j = i + 1; j < skuList.size(); j++) {
                        String s1 = skuList.get(i);
                        String s2 = skuList.get(j);
                        coOccurrence.computeIfAbsent(s1, k -> new HashMap<>())
                                .computeIfAbsent(s2, k -> new AtomicInteger(0)).incrementAndGet();
                        coOccurrence.computeIfAbsent(s2, k -> new HashMap<>())
                                .computeIfAbsent(s1, k -> new AtomicInteger(0)).incrementAndGet();
                    }
                }
            }

            int updated = 0;
            for (Map.Entry<String, Map<String, AtomicInteger>> sourceEntry : coOccurrence.entrySet()) {
                String sourceSku = sourceEntry.getKey();
                AtomicInteger sourceCount = skuTotalCount.getOrDefault(sourceSku, new AtomicInteger(1));

                for (Map.Entry<String, AtomicInteger> targetEntry : sourceEntry.getValue().entrySet()) {
                    String targetSku = targetEntry.getKey();
                    int coCount = targetEntry.getValue().get();

                    double support = (double) coCount / totalTransactions;
                    double confidence = (double) coCount / sourceCount.get();
                    double tgtCount = skuTotalCount.getOrDefault(targetSku, new AtomicInteger(1)).get();
                    double expectedProb = tgtCount / totalTransactions;
                    double lift = expectedProb > 0 ? confidence / expectedProb : 0.0;

                    if (support < minSupport || confidence < minConfidence) {
                        continue;
                    }

                    saveAssociation(tenantId, bizType, sourceSku, targetSku, coCount,
                            support, confidence, lift, totalTransactions, sourceCount.get(),
                            (int) tgtCount, startTime, endTime);
                    updated++;
                }
            }

            log.info("Association analysis for tenant={}, bizType={}: transactions={}, associations={}",
                    tenantId, bizType, totalTransactions, updated);
            totalUpdated += updated;
        }

        log.info("SKU association analysis completed: total associations={}", totalUpdated);
    }

    private void saveAssociation(String tenantId, String bizType, String sourceSku, String targetSku,
                                  int coCount, double support, double confidence, double lift,
                                  int totalTransactions, int sourceCount, int targetCount,
                                  LocalDateTime statStartTime, LocalDateTime statEndTime) {
        LambdaQueryWrapper<SkuAssociationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkuAssociationEntity::getTenantId, tenantId)
                .eq(SkuAssociationEntity::getBizType, bizType)
                .eq(SkuAssociationEntity::getSourceSkuId, sourceSku)
                .eq(SkuAssociationEntity::getTargetSkuId, targetSku)
                .eq(SkuAssociationEntity::getDeleted, 0)
                .last("LIMIT 1");

        SkuAssociationEntity existing = skuAssociationMapper.selectOne(wrapper);

        if (existing != null) {
            existing.setCoOccurrenceCount(coCount);
            existing.setSupport(support);
            existing.setConfidence(confidence);
            existing.setLift(lift);
            existing.setTotalTransactions(totalTransactions);
            existing.setSourceCount(sourceCount);
            existing.setTargetCount(targetCount);
            existing.setStatStartTime(statStartTime);
            existing.setStatEndTime(statEndTime);
            existing.setAlgorithm("cf");
            skuAssociationMapper.updateById(existing);
        } else {
            SkuAssociationEntity entity = new SkuAssociationEntity();
            entity.setTenantId(tenantId);
            entity.setBizType(bizType);
            entity.setSourceSkuId(sourceSku);
            entity.setTargetSkuId(targetSku);
            entity.setCoOccurrenceCount(coCount);
            entity.setSupport(support);
            entity.setConfidence(confidence);
            entity.setLift(lift);
            entity.setTotalTransactions(totalTransactions);
            entity.setSourceCount(sourceCount);
            entity.setTargetCount(targetCount);
            entity.setStatStartTime(statStartTime);
            entity.setStatEndTime(statEndTime);
            entity.setAlgorithm("cf");
            entity.setStatus(1);
            skuAssociationMapper.insert(entity);
        }
    }

    public void recordAddForRecommend(String tenantId, String bizType, String userId, String skuId) {
        if (!cartHubProperties.getRecommend().getEnable()) {
            return;
        }

        if (StringUtils.isAnyBlank(tenantId, bizType, userId, skuId)) {
            return;
        }

        try {
            String coKey = RedisKeyConstant.buildSkuCoOccurrenceKey(tenantId, bizType);
            Cart cart = cartRedisStorage.getCart(tenantId, bizType, userId);
            if (cart != null && cart.getItems() != null) {
                for (CartItem item : cart.getItems()) {
                    if (!item.getSkuId().equals(skuId)) {
                        String pairKey = item.getSkuId() + ":" + skuId;
                        stringRedisTemplate.opsForHash().increment(coKey, pairKey, 1);
                    }
                }
            }

            String recommendKey = RedisKeyConstant.buildRecommendKey(tenantId, bizType, userId);
            stringRedisTemplate.delete(recommendKey);
        } catch (Exception e) {
            log.warn("Record add for recommend failed: {}", e.getMessage());
        }
    }

}
