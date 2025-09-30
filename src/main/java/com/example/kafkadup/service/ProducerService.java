package com.example.kafkadup.service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;

/**
 * ProducerService that works with multiple Spring-Kafka versions:
 * - If KafkaTemplate.send(...) returns a CompletableFuture, use it directly.
 * - If it returns a ListenableFuture, convert it to a CompletableFuture.
 */
@Service
public class ProducerService {
    private final Logger log = LoggerFactory.getLogger(ProducerService.class);
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.topic:order.created}")
    private String topic;

    public ProducerService(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public String sendOne() {
        return sendOneWithKey(null);
    }

    public String sendOneWithKey(String providedKey) {
        final String id = (providedKey == null || providedKey.isBlank()) ? UUID.randomUUID().toString() : providedKey;
        final String payload = "order-id=" + id + "|ts=" + System.currentTimeMillis();
        log.info("[PRODUCER] Sending payload={} key={}", payload, id);

        // kafkaTemplate.send(...) may return either a CompletableFuture or a ListenableFuture
        Object rawFuture = kafkaTemplate.send(topic, id, payload);

        CompletableFuture<SendResult<String, String>> cf = adaptToCompletable(rawFuture);

        cf.whenComplete((result, ex) -> {
            if (ex == null && result != null && result.getRecordMetadata() != null) {
                log.info("[PRODUCER] Send succeeded topic={}, partition={}, offset={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } else if (ex != null) {
                log.warn("[PRODUCER] Send failed — producer did not get ack: {}", ex.getMessage(), ex);
            } else {
                log.warn("[PRODUCER] Send completed but result or metadata was null (unexpected).");
            }
        });

        return id;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, String>> adaptToCompletable(Object rawFuture) {
        if (rawFuture == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("kafkaTemplate.send returned null"));
        }

        // Case 1: Spring-Kafka 3.1+ where send(...) returns CompletableFuture<SendResult<...>>
        if (rawFuture instanceof CompletableFuture<?> cfAny) {
            return (CompletableFuture<SendResult<String, String>>) cfAny;
        }

        // Case 2: older Spring-Kafka returning ListenableFuture<SendResult<...>>
        if (rawFuture instanceof ListenableFuture<?> lfAny) {
            return toCompletableFuture((ListenableFuture<SendResult<String, String>>) lfAny);
        }

        // Unknown future type — attempt to wrap defensively
        return CompletableFuture.failedFuture(new IllegalStateException(
                "Unsupported future type returned by KafkaTemplate.send: " + rawFuture.getClass().getName()));
    }

    private static <T> CompletableFuture<T> toCompletableFuture(ListenableFuture<T> lf) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        lf.addCallback(cf::complete, cf::completeExceptionally);
        return cf;
    }
}
