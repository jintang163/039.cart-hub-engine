package com.carhub.service;

import com.carhub.common.util.JsonUtil;
import com.carhub.config.CartHubProperties;
import com.carhub.domain.model.Cart;
import com.carhub.domain.model.CartItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartNotificationService {

    private final CartHubProperties cartHubProperties;
    private final RestTemplate restTemplate;

    @Async
    public void sendExpireReminder(String tenantId, String bizType, String userId,
                                   Cart cart, int daysBeforeExpire) {
        if (!Boolean.TRUE.equals(cartHubProperties.getCleanup().getEnableNotification())) {
            return;
        }
        try {
            String channels = cartHubProperties.getCleanup().getNotifyChannels();
            if (StringUtils.isBlank(channels)) {
                return;
            }
            String[] channelArray = channels.split(",");
            for (String channel : channelArray) {
                try {
                    switch (channel.trim().toLowerCase()) {
                        case "wechat":
                            sendWechatNotification(tenantId, bizType, userId, cart, daysBeforeExpire);
                            break;
                        case "sms":
                            sendSmsNotification(tenantId, bizType, userId, cart, daysBeforeExpire);
                            break;
                        default:
                            log.warn("Unknown notification channel: {}", channel);
                    }
                } catch (Exception e) {
                    log.error("Send {} notification failed, userId={}", channel, userId, e);
                }
            }
            log.info("Expire reminder sent: tenantId={}, bizType={}, userId={}, daysBefore={}",
                    tenantId, bizType, userId, daysBeforeExpire);
        } catch (Exception e) {
            log.error("Send expire reminder error, userId={}", userId, e);
        }
    }

    @Async
    public void sendCleanupNotification(String tenantId, String bizType, String userId,
                                        Cart cart, String archiveReason) {
        if (!Boolean.TRUE.equals(cartHubProperties.getCleanup().getEnableNotification())) {
            return;
        }
        try {
            String channels = cartHubProperties.getCleanup().getNotifyChannels();
            if (StringUtils.isBlank(channels)) {
                return;
            }
            String[] channelArray = channels.split(",");
            for (String channel : channelArray) {
                try {
                    switch (channel.trim().toLowerCase()) {
                        case "wechat":
                            sendWechatCleanupNotification(tenantId, bizType, userId, cart, archiveReason);
                            break;
                        case "sms":
                            sendSmsCleanupNotification(tenantId, bizType, userId, cart, archiveReason);
                            break;
                        default:
                            log.warn("Unknown notification channel: {}", channel);
                    }
                } catch (Exception e) {
                    log.error("Send {} cleanup notification failed, userId={}", channel, userId, e);
                }
            }
            log.info("Cleanup notification sent: tenantId={}, bizType={}, userId={}, reason={}",
                    tenantId, bizType, userId, archiveReason);
        } catch (Exception e) {
            log.error("Send cleanup notification error, userId={}", userId, e);
        }
    }

    private void sendWechatNotification(String tenantId, String bizType, String userId,
                                        Cart cart, int daysBeforeExpire) {
        String notifyUrl = cartHubProperties.getCleanup().getNotifyApiUrl();
        if (StringUtils.isBlank(notifyUrl)) {
            log.debug("Notify API URL not configured, skip wechat notification");
            return;
        }
        Map<String, Object> payload = buildNotificationPayload(tenantId, bizType, userId, cart,
                "cart_expire_reminder", daysBeforeExpire, null);
        payload.put("channel", "wechat");
        payload.put("templateId", cartHubProperties.getCleanup().getWechatTemplateId());
        doSendNotification(notifyUrl, payload);
    }

    private void sendSmsNotification(String tenantId, String bizType, String userId,
                                     Cart cart, int daysBeforeExpire) {
        String notifyUrl = cartHubProperties.getCleanup().getNotifyApiUrl();
        if (StringUtils.isBlank(notifyUrl)) {
            log.debug("Notify API URL not configured, skip sms notification");
            return;
        }
        Map<String, Object> payload = buildNotificationPayload(tenantId, bizType, userId, cart,
                "cart_expire_reminder", daysBeforeExpire, null);
        payload.put("channel", "sms");
        payload.put("templateId", cartHubProperties.getCleanup().getSmsTemplateId());
        doSendNotification(notifyUrl, payload);
    }

    private void sendWechatCleanupNotification(String tenantId, String bizType, String userId,
                                               Cart cart, String archiveReason) {
        String notifyUrl = cartHubProperties.getCleanup().getNotifyApiUrl();
        if (StringUtils.isBlank(notifyUrl)) {
            return;
        }
        Map<String, Object> payload = buildNotificationPayload(tenantId, bizType, userId, cart,
                "cart_cleanup_notification", 0, archiveReason);
        payload.put("channel", "wechat");
        payload.put("templateId", cartHubProperties.getCleanup().getWechatTemplateId());
        doSendNotification(notifyUrl, payload);
    }

    private void sendSmsCleanupNotification(String tenantId, String bizType, String userId,
                                            Cart cart, String archiveReason) {
        String notifyUrl = cartHubProperties.getCleanup().getNotifyApiUrl();
        if (StringUtils.isBlank(notifyUrl)) {
            return;
        }
        Map<String, Object> payload = buildNotificationPayload(tenantId, bizType, userId, cart,
                "cart_cleanup_notification", 0, archiveReason);
        payload.put("channel", "sms");
        payload.put("templateId", cartHubProperties.getCleanup().getSmsTemplateId());
        doSendNotification(notifyUrl, payload);
    }

    private Map<String, Object> buildNotificationPayload(String tenantId, String bizType, String userId,
                                                          Cart cart, String templateType,
                                                          int daysBeforeExpire, String archiveReason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("bizType", bizType);
        payload.put("userId", userId);
        payload.put("templateType", templateType);

        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("itemCount", cart.getItemCount() != null ? cart.getItemCount() : 0);
        templateParams.put("totalAmount", cart.getTotalAmount() != null ? cart.getTotalAmount() : BigDecimal.ZERO);
        templateParams.put("daysBeforeExpire", daysBeforeExpire);
        templateParams.put("archiveReason", archiveReason);

        if (cart.getExpireTime() != null) {
            LocalDateTime expireDateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(cart.getExpireTime()), ZoneId.systemDefault());
            templateParams.put("expireTime", expireDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        }

        if (cart.getItems() != null && !cart.getItems().isEmpty()) {
            List<Map<String, Object>> itemList = new ArrayList<>();
            int displayCount = Math.min(cart.getItems().size(), 3);
            for (int i = 0; i < displayCount; i++) {
                CartItem item = cart.getItems().get(i);
                Map<String, Object> itemMap = new HashMap<>();
                itemMap.put("skuId", item.getSkuId());
                itemMap.put("itemName", item.getItemName());
                itemMap.put("itemImage", item.getItemImage());
                itemMap.put("quantity", item.getQuantity());
                itemMap.put("unitPrice", item.getUnitPrice());
                itemList.add(itemMap);
            }
            templateParams.put("items", itemList);
            if (cart.getItems().size() > 3) {
                templateParams.put("moreCount", cart.getItems().size() - 3);
            }
        }

        payload.put("templateParams", templateParams);
        return payload;
    }

    private void doSendNotification(String notifyUrl, Map<String, Object> payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(JsonUtil.toJson(payload), headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    notifyUrl, HttpMethod.POST, request, String.class);
            if (response.getStatusCode() == HttpStatus.OK) {
                log.debug("Notification sent successfully: {}", payload.get("templateType"));
            } else {
                log.warn("Notification API returned non-200 status: {}, body: {}",
                        response.getStatusCode(), response.getBody());
            }
        } catch (Exception e) {
            log.warn("Call notification API failed: {}", e.getMessage());
        }
    }

}
