package com.example.kafkaapmdemo.service;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.util.UUID;

@Service
public class MessageProducerService {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageProducerService.class);
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    public void sendMessage(String topic, String key, String message) {
        String operationId = UUID.randomUUID().toString();
        
        // Vytvoř span pro Kafka operaci
        Span kafkaSpan = ElasticApm.currentSpan()
            .startSpan("messaging", "kafka", "send")
            .setName("kafka-send-" + topic);
        
        try {
            logger.info("Sending Kafka message - Topic: {}, Key: {}, OperationId: {}", 
                topic, key, operationId);
            
            // Nastav span labels
            kafkaSpan.addLabel("kafka.topic", topic);
            kafkaSpan.addLabel("kafka.key", key);
            kafkaSpan.addLabel("message.size", message.length());
            kafkaSpan.addLabel("operation.id", operationId);
            
            // Pošli zprávu asynchronně
            ListenableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, message);
            
            future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                @Override
                public void onSuccess(SendResult<String, String> result) {
                    logger.info("Kafka message sent successfully - Topic: {}, Partition: {}, Offset: {}, OperationId: {}", 
                        topic, 
                        result.getRecordMetadata().partition(), 
                        result.getRecordMetadata().offset(),
                        operationId);
                    
                    // Přidej metadata z výsledku
                    kafkaSpan.addLabel("kafka.partition", result.getRecordMetadata().partition());
                    kafkaSpan.addLabel("kafka.offset", result.getRecordMetadata().offset());
                    kafkaSpan.addLabel("kafka.timestamp", result.getRecordMetadata().timestamp());
                    kafkaSpan.addLabel("result", "success");
                    
                    kafkaSpan.end();
                }
                
                @Override
                public void onFailure(Throwable exception) {
                    logger.error("Failed to send Kafka message - Topic: {}, OperationId: {}, Error: {}", 
                        topic, operationId, exception.getMessage(), exception);
                    
                    kafkaSpan.captureException(exception);
                    kafkaSpan.addLabel("result", "failure");
                    kafkaSpan.end();
                }
            });
            
        } catch (Exception e) {
            logger.error("Error creating Kafka message - Topic: {}, OperationId: {}, Error: {}", 
                topic, operationId, e.getMessage(), e);
            
            kafkaSpan.captureException(e);
            kafkaSpan.addLabel("result", "error");
            kafkaSpan.end();
            
            throw new RuntimeException("Failed to send message to Kafka topic: " + topic, e);
        }
    }
}