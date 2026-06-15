package com.carhub.mq;

import com.carhub.common.constant.CartConstant;
import com.carhub.common.util.JsonUtil;
import com.carhub.domain.message.ProductChangeMessage;
import com.carhub.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductChangeListener {

    private final CartService cartService;

    @KafkaListener(topics = CartConstant.TOPIC_PRODUCT_CHANGE,
            groupId = CartConstant.GROUP_CART_PRODUCT_CHANGE,
            containerFactory = "kafkaListenerContainerFactory")
    public void onProductChange(List<String> messages, Acknowledgment ack) {
        if (messages == null || messages.isEmpty()) {
            ack.acknowledge();
            return;
        }
        log.info("ProductChangeListener received: {} messages", messages.size());
        int success = 0;
        int failed = 0;
        for (String msg : messages) {
            try {
                ProductChangeMessage change = JsonUtil.fromJson(msg, ProductChangeMessage.class);
                if (change != null && StringUtils.isNoneBlank(change.getTenantId(),
                        change.getBizType(), change.getSkuId())) {
                    cartService.recalculateForSku(change.getTenantId(),
                            change.getBizType(), change.getSkuId());
                    success++;
                }
            } catch (Exception e) {
                failed++;
                log.error("process product change message error: {}", msg, e);
            }
        }
        log.info("ProductChangeListener done: success={}, failed={}", success, failed);
        ack.acknowledge();
    }

}
