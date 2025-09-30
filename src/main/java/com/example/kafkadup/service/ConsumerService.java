/*
package com.example.kafkadup.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

*/
/**
 * Simple consumer that logs key, offset and value. If the same key appears at different offsets,
 * you'll see duplicate messages (this is the thing we want to reproduce).
 *//*

@Service
public class ConsumerService {
    private final Logger log = LoggerFactory.getLogger(ConsumerService.class);

    @KafkaListener(topics = "${app.topic:order.created}", groupId = "dup-repro-group")
    public void listen(ConsumerRecord<String, String> record) {
        log.info("[CONSUMER] received key={} offset={} value={}",
                record.key(), record.offset(), record.value());
    }
}
*/
