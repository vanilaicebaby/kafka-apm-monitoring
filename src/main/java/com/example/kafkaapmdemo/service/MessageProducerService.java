// src/main/java/com/example/kafkaapmdemo/service/MessageProducerService.java
package com.example.kafkaapmdemo.service;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Span;
import co.elastic.apm.api.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

@Service
public class MessageProducerService {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageProducerService.class);
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    public void sendMessage(String topic, String key, String message) {
        // Vytvoření custom APM span pro tracking
        Span span = ElasticApm.currentSpan()
            .startSpan("kafka", "send", "producer")
            .setName("kafka-send-" + topic);
        
        try {
            logger.info("Sending message to topic {}: {}", topic, message);
            
            ListenableFuture<SendResult<String, String>> future = 
                kafkaTemplate.send(topic, key, message);
            
            future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                @Override
                public void onSuccess(SendResult<String, String> result) {
                    logger.info("Message sent successfully to topic {} with offset {}",
                        topic, result.getRecordMetadata().offset());
                    span.setLabel("kafka.offset", result.getRecordMetadata().offset());
                    span.setLabel("kafka.partition", result.getRecordMetadata().partition());
                    span.end();
                }
                
                @Override
                public void onFailure(Throwable exception) {
                    logger.error("Failed to send message to topic {}: {}", topic, exception.getMessage());
                    span.captureException(exception);
                    span.end();
                }
            });
            
        } catch (Exception e) {
            logger.error("Error sending message: {}", e.getMessage());
            span.captureException(e);
            span.end();
            throw e;
        }
    }
}