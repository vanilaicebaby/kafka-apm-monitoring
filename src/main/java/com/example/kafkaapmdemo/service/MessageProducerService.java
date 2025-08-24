// MessageProducerService.java - FIXED version
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
        // NETVOŘTE novou transakci! Použijte současnou z HTTP controlleru
        Transaction currentTransaction = ElasticApm.currentTransaction();
        
        // Generuj tracing IDs
        String activityId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        String transparent = "true";
        
        logger.info("Sending message to topic {} with ActivityId: {}, RequestId: {}", 
            topic, activityId, requestId);
        
        // Vytvoř span pro Kafka send operaci jako dítě současné transakce
        Span kafkaSpan = ElasticApm.currentSpan()
            .startSpan("kafka", "send", "producer")
            .setName("kafka-send-" + topic);
        
        try {
            // Vytvoř ProducerRecord s custom headers
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, message);
            Headers headers = record.headers();
            
            // Přidej distributed tracing headers
            headers.add("ActivityId", activityId.getBytes(StandardCharsets.UTF_8));
            headers.add("RequestId", requestId.getBytes(StandardCharsets.UTF_8));
            headers.add("Transparent", transparent.getBytes(StandardCharsets.UTF_8));
            
            // Přidej APM tracing context do headers
            kafkaSpan.injectTraceHeaders((name, value) -> {
                headers.add("apm-" + name, value.getBytes(StandardCharsets.UTF_8));
            });
            
            // Nastav span labels
            kafkaSpan.setLabel("kafka.topic", topic);
            kafkaSpan.setLabel("kafka.key", key);
            kafkaSpan.setLabel("message.size", message.length());
            kafkaSpan.setLabel("activityId", activityId);
            kafkaSpan.setLabel("requestId", requestId);
            kafkaSpan.setLabel("transparent", transparent);
            
            // Pošli zprávu
            ListenableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);
            
            future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                @Override
                public void onSuccess(SendResult<String, String> result) {
                    logger.info("Message sent successfully - Topic: {}, Offset: {}, ActivityId: {}", 
                        topic, result.getRecordMetadata().offset(), activityId);
                    
                    // Přidej metadata z výsledku
                    kafkaSpan.setLabel("kafka.offset", result.getRecordMetadata().offset());
                    kafkaSpan.setLabel("kafka.partition", result.getRecordMetadata().partition());
                    kafkaSpan.setLabel("kafka.timestamp", result.getRecordMetadata().timestamp());
                    
                    // Nastav výsledek na současné transakci (ne na span)
                    if (currentTransaction != null) {
                        currentTransaction.setResult("success");
                    }
                    
                    // Konec pouze spanu, ne transakce
                    kafkaSpan.end();
                }
                
                @Override
                public void onFailure(Throwable exception) {
                    logger.error("Failed to send message - Topic: {}, ActivityId: {}, Error: {}", 
                        topic, activityId, exception.getMessage());
                    
                    kafkaSpan.captureException(exception);
                    
                    if (currentTransaction != null) {
                        currentTransaction.captureException(exception);
                        currentTransaction.setResult("error");
                    }
                    
                    // Konec pouze spanu, ne transakce
                    kafkaSpan.end();
                }
            });
            
            // Čekáme na dokončení - pro synchronní chování
            // POZOR: Toto blokuje thread, ale zajistí to správné ukončení spanu
            try {
                SendResult<String, String> result = future.get();
                logger.info("Message send completed synchronously - Offset: {}", 
                    result.getRecordMetadata().offset());
            } catch (Exception e) {
                logger.error("Error waiting for Kafka send result: {}", e.getMessage());
                kafkaSpan.captureException(e);
                throw new RuntimeException("Failed to send message to Kafka", e);
            }
            
        } catch (Exception e) {
            logger.error("Error creating Kafka message: {}", e.getMessage(), e);
            kafkaSpan.captureException(e);
            
            if (currentTransaction != null) {
                currentTransaction.captureException(e);
                currentTransaction.setResult("error");
            }
            
            kafkaSpan.end();
            throw new RuntimeException("Failed to send message", e);
        }
    }
}