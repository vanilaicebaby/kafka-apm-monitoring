// src/main/java/com/example/kafkaapmdemo/service/MessageConsumerService.java
package com.example.kafkaapmdemo.service;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
public class MessageConsumerService {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageConsumerService.class);
    
    @KafkaListener(topics = "cards", groupId = "demo-group")
    public void consume(@Payload String message,
                       @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                       @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
                       @Header(KafkaHeaders.OFFSET) long offset) {
        
        // APM span pro consumer
        Span span = ElasticApm.currentSpan()
            .startSpan("kafka", "consume", "consumer")
            .setName("kafka-consume-" + topic);
        
        try {
            logger.info("Consumed message from topic {}, partition {}, offset {}: {}", 
                topic, partition, offset, message);
            
            // Simulace zpracování
            Thread.sleep(100);
            
            span.setLabel("kafka.topic", topic);
            span.setLabel("kafka.partition", partition);
            span.setLabel("kafka.offset", offset);
            span.setLabel("message.length", message.length());
            
            logger.info("Message processed successfully");
            
        } catch (Exception e) {
            logger.error("Error processing message: {}", e.getMessage());
            span.captureException(e);
        } finally {
            span.end();
        }
    }
}