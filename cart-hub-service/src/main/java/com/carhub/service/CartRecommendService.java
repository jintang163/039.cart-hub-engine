package com.carhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carhub.common.constant.RedisKeyConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.entity.CartHistoryEntity;
import com.carhub.domain.entity.SkuAssociationEntity;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import com.carhub.domain.vo.RecommendItemVO;
import com.carhub.mapper.CartHistoryMapper;
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
    private final CartHistoryMapper cartHistoryMapper;
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
                    if (!sourceSkus.contains(assoc.getSourceSkuId())) {
                        sourceSkus.add(assoc.getSourceSkuId());
                    }
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

        return recommendMap.values().stream()
                .sorted(Comparator.comparingDouble(RecommendItemVO::getScore).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    private List<RecommendItemVO> recommendByUserHistory(String tenantId, String bizType,
                                                          String userId, Integer topN) {
        LambdaQueryWrapper<CartHistoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartHistoryEntity::getTenantId, tenantId)
                .eq(CartHistoryEntity::getBizType, bizType)
                .eq(CartHistoryEntity::getUserId, userId)
                .eq(CartHistoryEntity::getAction, "add")
                .orderByDesc(CartHistoryEntity::getCreateTime)
                .last("LIMIT 50");

        List<CartHistoryEntity> histories = cartHistoryMapper.selectList(wrapper);

        Map<String, AtomicInteger> skuFreq = new HashMap<>();
        for (CartHistoryEntity h : histories) {
            if (StringUtils.isNotBlank(h.getSkuId())) {
                skuFreq.computeIfAbsent(h.getSkuId(), k -> new AtomicInteger(0)).incrementAndGet();
            }
        }

        return skuFreq.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                        Comparator.comparingInt(AtomicInteger::get)).reversed())
                .limit(topN)
                .map(e -> RecommendItemVO.builder()
                        .skuId(e.getKey())
                        .score((double) e.getValue().get())
                        .recommendReason("根据您的加购历史推荐")
                        .coOccurrenceCount(e.getValue().get())
                        .build())
                .collect(Collectors.toList());
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
        log.info("Starting SKU association analysis...");
        int recentDays = cartHubProperties.getRecommend().getAnalyzeRecentDays();
        int maxRecords = cartHubProperties.getRecommend().getMaxHistoryRecords();
        LocalDateTime startTime = LocalDateTime.now().minusDays(recentDays);
        LocalDateTime endTime = LocalDateTime.now();

        LambdaQueryWrapper<CartHistoryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CartHistoryEntity::getAction, "add")
                .ge(CartHistoryEntity::getCreateTime, startTime)
                .orderByDesc(CartHistoryEntity::getCreateTime)
                .last("LIMIT " + maxRecords);

        List<CartHistoryEntity> histories = cartHistoryMapper.selectList(wrapper);
        if (histories == null || histories.isEmpty()) {
            log.info("No history data for association analysis");
            return;
        }

        Map<String, Set<String>> userSkuMap = new HashMap<>();
        for (CartHistoryEntity h : histories) {
            if (StringUtils.isAnyBlank(h.getTenantId(), h.getBizType(), h.getUserId(), h.getSkuId())) {
                continue;
            }
            String userKey = h.getTenantId() + ":" + h.getBizType() + ":" + h.getUserId();
            userSkuMap.computeIfAbsent(userKey, k -> new HashSet<>()).add(h.getSkuId());
        }

        Map<String, Map<String, AtomicInteger>> coOccurrence = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> skuTotalCount = new ConcurrentHashMap<>();

        for (Map.Entry<String, Set<String>> entry : userSkuMap.entrySet()) {
            Set<String> skus = entry.getValue();
            for (String sku : skus) {
                skuTotalCount.computeIfAbsent(sku, k -> new AtomicInteger(0)).incrementAndGet();
            }

            List<String> skuList = new ArrayList<>(skus);
            for (int i = 0; i < skuList.size(); i++) {
                for (int j = i + 1; j < skuList.size(); j++) {
                    String s1 = skuList.get(i);
                    String s2 = skuList.get(j);
                    coOccurrence.computeIfAbsent(s1, k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(s2, k -> new AtomicInteger(0)).incrementAndGet();
                    coOccurrence.computeIfAbsent(s2, k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(s1, k -> new AtomicInteger(0)).incrementAndGet();
                }
            }
        }

        int totalTransactions = userSkuMap.size();
        Double minSupport = cartHubProperties.getRecommend().getMinSupport();
        Double minConfidence = cartHubProperties.getRecommend().getMinConfidence();

        int updated = 0;
        for (Map.Entry<String, Map<String, AtomicInteger>> sourceEntry : coOccurrence.entrySet()) {
            String sourceSku = sourceEntry.getKey();
            AtomicInteger sourceCount = skuTotalCount.getOrDefault(sourceSku, new AtomicInteger(1));

            for (Map.Entry<String, AtomicInteger> targetEntry : sourceEntry.getValue().entrySet()) {
                String targetSku = targetEntry.getKey();
                int coCount = targetEntry.getValue().get();

                double support = (double) coCount / totalTransactions;
                double confidence = (double) coCount / sourceCount.get();
                double targetCount = (double) skuTotalCount.getOrDefault(targetSku, new AtomicInteger(1)).get();
                double expectedProb = targetCount / totalTransactions;
                double lift = expectedProb > 0 ? confidence / expectedProb : 0.0;

                if (support < minSupport || confidence < minConfidence) {
                    continue;
                }

                saveAssociation(sourceSku, targetSku, coCount, support, confidence, lift,
                        totalTransactions, sourceCount.get(), (int) targetCount,
                        startTime, endTime);
                updated++;
            }
        }

        log.info("SKU association analysis completed: totalTransactions={}, associations={}",
                totalTransactions, updated);
    }

    private void saveAssociation(String sourceSku, String targetSku, int coCount,
                                  double support, double confidence, double lift,
                                  int totalTransactions, int sourceCount, int targetCount,
                                  LocalDateTime statStartTime, LocalDateTime statEndTime) {
        LambdaQueryWrapper<SkuAssociationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SkuAssociationEntity::getSourceSkuId, sourceSku)
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
            entity.setTenantId("default");
            entity.setBizType("ecommerce");
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
