package com.carhub.mq;

import com.alibaba.fastjson.JSON;
import com.carhub.config.AnalyticsProperties;
import com.carhub.domain.dto.CartEventDTO;
import com.carhub.service.CartAnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartEventConsumer {

    private final CartAnalyticsService cartAnalyticsService;
    private final AnalyticsProperties analyticsProperties;

    private static final int BATCH_WRITE_SIZE = 100;
    private static final long BATCH_TIMEOUT_MS = 5000;

    private final BlockingQueue<CartEventDTO> eventQueue = new LinkedBlockingQueue<>(10000);
    private final AtomicInteger queueSize = new AtomicInteger(0);

    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        if (analyticsProperties.isEnableKafkaConsumer()) {
            Thread batchWriterThread = new Thread(this::batchWriteLoop, "cart-event-batch-writer");
            batchWriterThread.setDaemon(true);
            batchWriterThread.start();
            log.info("Cart event batch writer thread started");
        }
    }

    @KafkaListener(topics = "${analytics.kafka-topic:cart_event_topic}",
            groupId = "cart-analytics-group",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(List<String> messages, Acknowledgment ack) {
        if (messages == null || messages.isEmpty()) {
            ack.acknowledge();
            return;
        }

        int success = 0;
        int failed = 0;

        for (String message : messages) {
            try {
                if (StringUtils.isBlank(message)) {
                    failed++;
                    continue;
                }

                CartEventDTO event = JSON.parseObject(message, CartEventDTO.class);
                if (event == null || StringUtils.isBlank(event.getEventType())) {
                    log.warn("Invalid event message: {}", message);
                    failed++;
                    continue;
                }

                boolean offered = eventQueue.offer(event, 50, TimeUnit.MILLISECONDS);
                if (!offered) {
                    log.warn("Event queue is full, writing directly to ClickHouse: eventType={}", event.getEventType());
                    cartAnalyticsService.insertEventToClickHouse(event);
                } else {
                    queueSize.incrementAndGet();
                }
                success++;

            } catch (Exception e) {
                failed++;
                log.error("Failed to process cart event message: {}", message, e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("CartEventConsumer processed: success={}, failed={}, queueSize={}",
                    success, failed, queueSize.get());
        }

        ack.acknowledge();
    }

    private void batchWriteLoop() {
        while (running) {
            try {
                List<CartEventDTO> batch = new ArrayList<>(BATCH_WRITE_SIZE);
                long startTime = System.currentTimeMillis();

                while (batch.size() < BATCH_WRITE_SIZE &&
                        (System.currentTimeMillis() - startTime) < BATCH_TIMEOUT_MS) {
                    CartEventDTO event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        batch.add(event);
                        queueSize.decrementAndGet();
                    } else if (batch.isEmpty()) {
                        break;
                    }
                }

                if (!batch.isEmpty()) {
                    try {
                        cartAnalyticsService.insertEventsBatch(batch);
                        log.debug("Batch written to ClickHouse: size={}, queueSize={}", batch.size(), queueSize.get());
                    } catch (Exception e) {
                        log.error("Failed to batch write events to ClickHouse, size={}", batch.size(), e);
                        for (CartEventDTO event : batch) {
                            try {
                                cartAnalyticsService.insertEventToClickHouse(event);
                            } catch (Exception ex) {
                                log.error("Failed to insert single event: eventId={}", event.getEventId(), ex);
                            }
                        }
                    }
                }

            } catch (InterruptedException e) {
                log.warn("Batch writer thread interrupted");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Batch writer loop error", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public int getQueueSize() {
        return queueSize.get();
    }
}
