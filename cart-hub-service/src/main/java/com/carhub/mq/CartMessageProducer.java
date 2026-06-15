package com.carhub.mq;

import com.carhub.common.constant.CartConstant;
import com.carhub.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartMessageProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public boolean sendProductChange(Object message) {
        return send(CartConstant.TOPIC_PRODUCT_CHANGE, message);
    }

    public boolean sendCartSnapshot(Object message) {
        return send(CartConstant.TOPIC_CART_SNAPSHOT, message);
    }

    public boolean sendCartSync(Object message) {
        return send(CartConstant.TOPIC_CART_SYNC, message);
    }

    public boolean send(String topic, Object message) {
        try {
            String json = JsonUtil.toJson(message);
            ListenableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, json);
            future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                @Override
                public void onSuccess(SendResult<String, String> result) {
                    if (log.isDebugEnabled()) {
                        log.debug("send kafka success: topic={}, offset={}",
                                topic, result.getRecordMetadata().offset());
                    }
                }
                @Override
                public void onFailure(Throwable ex) {
                    log.error("send kafka failed: topic={}", topic, ex);
                }
            });
            return true;
        } catch (Exception e) {
            log.error("send kafka exception: topic={}", topic, e);
            return false;
        }
    }

    public boolean sendSync(String topic, Object message, long timeoutMs) {
        try {
            String json = JsonUtil.toJson(message);
            SendResult<String, String> result = kafkaTemplate
                    .send(topic, json)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) {
                log.debug("sendSync success: topic={}, offset={}",
                        topic, result.getRecordMetadata().offset());
            }
            return true;
        } catch (Exception e) {
            log.error("sendSync failed: topic={}", topic, e);
            return false;
        }
    }

    public boolean batchSend(String topic, List<?> messages) {
        if (messages == null || messages.isEmpty()) return false;
        int success = 0;
        for (Object msg : messages) {
            if (send(topic, msg)) success++;
        }
        return success == messages.size();
    }

}
