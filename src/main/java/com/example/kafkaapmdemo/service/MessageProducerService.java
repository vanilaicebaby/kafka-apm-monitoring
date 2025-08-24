// MessageProducerService.java - s distributed tracing headers
package com.example.kafkaapmdemo.service;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Span;
import co.elastic.apm.api.Transaction;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class MessageProducerService {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageProducerService.class);
    
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    
    public void sendMessage(String topic, String key, String message) {
        // Vytvoř APM transakci pro celý flow
        Transaction transaction = ElasticApm.startTransaction();
        transaction.setName("kafka-send-message");
        transaction.setType("messaging");
        
        try {
            // Generuj tracing IDs
            String activityId = UUID.randomUUID().toString();
            String requestId = UUID.randomUUID().toString();
            String transparent = "true";
            
            logger.info("Sending message to topic {} with ActivityId: {}, RequestId: {}", 
                topic, activityId, requestId);
            
            // Vytvoř span pro Kafka send operaci
            Span kafkaSpan = transaction.startSpan("kafka", "send", "producer");
            kafkaSpan.setName("kafka-send-" + topic);
            
            try {
                // Vytvoř ProducerRecord s custom headers
                ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, message);
                Headers headers = record.headers();
                
                // Přidej distributed tracing headers
                headers.add("ActivityId", activityId.getBytes(StandardCharsets.UTF_8));
                headers.add("RequestId", requestId.getBytes(StandardCharsets.UTF_8));
                headers.add("Transparent", transparent.getBytes(StandardCharsets.UTF_8));
                
                // Přidej APM tracing context do headers (pro cross-service tracing)
                ElasticApm.currentSpan().injectTraceHeaders((name, value) -> {
                    headers.add("apm-" + name, value.getBytes(StandardCharsets.UTF_8));
                });
                
                // Nastav span labels pro lepší observability
                kafkaSpan.setLabel("kafka.topic", topic);
                kafkaSpan.setLabel("kafka.key", key);
                kafkaSpan.setLabel("message.size", message.length());
                kafkaSpan.setLabel("activityId", activityId);
                kafkaSpan.setLabel("requestId", requestId);
                kafkaSpan.setLabel("transparent", transparent);
                
                // Pošli zprávu
                ListenableFuture<SendResult<String, String>> future = 
                    kafkaTemplate.send(record);
                
                future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                    @Override
                    public void onSuccess(SendResult<String, String> result) {
                        logger.info("Message sent successfully - Topic: {}, Offset: {}, ActivityId: {}", 
                            topic, result.getRecordMetadata().offset(), activityId);
                        
                        kafkaSpan.setLabel("kafka.offset", result.getRecordMetadata().offset());
                        kafkaSpan.setLabel("kafka.partition", result.getRecordMetadata().partition());
                        transaction.setResult("success");
                        
                        kafkaSpan.end();
                        transaction.end();
                    }
                    
                    @Override
                    public void onFailure(Throwable exception) {
                        logger.error("Failed to send message - Topic: {}, ActivityId: {}, Error: {}", 
                            topic, activityId, exception.getMessage());
                        
                        kafkaSpan.captureException(exception);
                        transaction.captureException(exception);
                        transaction.setResult("error");
                        
                        kafkaSpan.end();
                        transaction.end();
                    }
                });
                
            } catch (Exception e) {
                logger.error("Error creating Kafka message: {}", e.getMessage());
                kafkaSpan.captureException(e);
                kafkaSpan.end();
                throw e;
            }
            
        } catch (Exception e) {
            logger.error("Error in sendMessage: {}", e.getMessage());
            transaction.captureException(e);
            transaction.setResult("error");
            transaction.end();
            throw e;
        }
    }
}