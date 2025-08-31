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
        // Generuj business identifikátory
        String activityId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        
        // Získej současnou transakci (z HTTP requestu)
        Transaction currentTransaction = ElasticApm.currentTransaction();
        
        // Vytvoř span pro Kafka operaci
        Span kafkaSpan = ElasticApm.currentSpan()
            .startSpan("messaging", "kafka", "send")
            .setName("kafka-send-" + topic);
        
        try {
            logger.info("Sending Kafka message - Topic: {}, Key: {}, ActivityId: {}, RequestId: {}", 
                topic, key, activityId, requestId);
            
            // Vytvoř ProducerRecord s custom headers
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, message);
            Headers headers = record.headers();
            
            // Přidej business headers
            headers.add("ActivityId", activityId.getBytes(StandardCharsets.UTF_8));
            headers.add("RequestId", requestId.getBytes(StandardCharsets.UTF_8));
            headers.add("X-Source-Service", "kafka-producer-java".getBytes(StandardCharsets.UTF_8));
            
            // OpenTelemetry/APM distributed tracing headers
            if (currentTransaction != null) {
                String traceId = currentTransaction.getTraceId();
                String spanId = kafkaSpan.getId();
                
                // W3C Trace Context standard format
                String traceparent = String.format("00-%s-%s-01", traceId, spanId);
                headers.add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
                
                // APM specific headers
                headers.add("x-trace-id", traceId.getBytes(StandardCharsets.UTF_8));
                headers.add("x-span-id", spanId.getBytes(StandardCharsets.UTF_8));
            }
            
            // Elastic APM injection (pokud podporuje)
            kafkaSpan.injectTraceHeaders((name, value) -> {
                headers.add("apm-" + name, value.getBytes(StandardCharsets.UTF_8));
            });
            
            // Labels pro APM
            kafkaSpan.addLabel("kafka.topic", topic);
            kafkaSpan.addLabel("kafka.key", key);
            kafkaSpan.addLabel("message.size", message.length());
            kafkaSpan.addLabel("activityId", activityId);
            kafkaSpan.addLabel("requestId", requestId);
            kafkaSpan.addLabel("business.operation", "kafka-send");
            kafkaSpan.addLabel("source.service", "kafka-producer-java");
            
            if (currentTransaction != null) {
                currentTransaction.addLabel("kafka.activityId", activityId);
                currentTransaction.addLabel("kafka.requestId", requestId);
                currentTransaction.addLabel("kafka.topic", topic);
                currentTransaction.addLabel("distributed.trace.initiated", "true");
            }
            
            // Pošli zprávu
            ListenableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);
            
            future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                @Override
                public void onSuccess(SendResult<String, String> result) {
                    logger.info("Kafka message sent successfully - Topic: {}, Partition: {}, Offset: {}, ActivityId: {}", 
                        topic, 
                        result.getRecordMetadata().partition(), 
                        result.getRecordMetadata().offset(),
                        activityId);
                    
                    kafkaSpan.addLabel("kafka.partition", result.getRecordMetadata().partition());
                    kafkaSpan.addLabel("kafka.offset", result.getRecordMetadata().offset());
                    kafkaSpan.addLabel("kafka.timestamp", result.getRecordMetadata().timestamp());
                    kafkaSpan.addLabel("result", "success");
                    
                    kafkaSpan.end();
                }
                
                @Override
                public void onFailure(Throwable exception) {
                    logger.error("Failed to send Kafka message - Topic: {}, ActivityId: {}, Error: {}", 
                        topic, activityId, exception.getMessage(), exception);
                    
                    kafkaSpan.captureException(exception);
                    kafkaSpan.addLabel("result", "failure");
                    kafkaSpan.addLabel("error.message", exception.getMessage());
                    kafkaSpan.end();
                }
            });
            
        } catch (Exception e) {
            logger.error("Error creating Kafka message - Topic: {}, ActivityId: {}, Error: {}", 
                topic, activityId, e.getMessage(), e);
            
            kafkaSpan.captureException(e);
            kafkaSpan.addLabel("result", "error");
            kafkaSpan.addLabel("error.message", e.getMessage());
            kafkaSpan.end();
            
            throw new RuntimeException("Failed to send message to Kafka topic: " + topic, e);
        }
    }
}