package com.example.kafkaapmdemo.controller;

import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Transaction;
import com.example.kafkaapmdemo.service.MessageProducerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageController.class);
    
    @Autowired
    private MessageProducerService producerService;
    
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, String> request) {
        Transaction transaction = ElasticApm.currentTransaction();
        
        String httpRequestId = UUID.randomUUID().toString();
        
        try {
            String message = request.get("message");
            String topic = request.getOrDefault("topic", "cards");
            String key = request.getOrDefault("key", "default-key");
            
            logger.info("Processing HTTP message send request - HttpRequestId: {}, Topic: {}, Key: {}", 
                httpRequestId, topic, key);
            
            if (transaction != null) {
                transaction.setName("POST /api/messages/send");
                transaction.setType("request");
                // Labels
                transaction.addLabel("http.requestId", httpRequestId);
                transaction.addLabel("http.endpoint", "/api/messages/send");
                transaction.addLabel("http.method", "POST");
                transaction.addLabel("kafka.target.topic", topic);
                transaction.addLabel("kafka.target.key", key);
                transaction.addLabel("message.length", message != null ? message.length() : 0);
                transaction.addLabel("business.operation", "message-send");
                transaction.addLabel("service.name", "kafka-producer-java");
                transaction.addLabel("service.component", "http-api");
            }
            
            if (message == null || message.trim().isEmpty()) {
                logger.warn("Empty message received - HttpRequestId: {}", httpRequestId);
                if (transaction != null) {
                    transaction.setResult("error");
                    transaction.addLabel("error.type", "validation_error");
                    transaction.addLabel("error.reason", "empty_message");
                }
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message cannot be empty", "httpRequestId", httpRequestId));
            }
            
            producerService.sendMessage(topic, key, message);
            
            logger.info("Message sent successfully - HttpRequestId: {}", httpRequestId);
            if (transaction != null) {
                transaction.setResult("success");
                transaction.addLabel("processing.status", "completed");
            }
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Message sent to topic: " + topic,
                "key", key,
                "httpRequestId", httpRequestId,
                "timestamp", LocalDateTime.now().toString()
            ));
            
        } catch (Exception e) {
            logger.error("Error processing HTTP message send request - HttpRequestId: {}, Error: {}", 
                httpRequestId, e.getMessage(), e);
            
            if (transaction != null) {
                transaction.captureException(e);
                transaction.setResult("error");
                transaction.addLabel("error.type", "processing_error");
                transaction.addLabel("error.message", e.getMessage());
                transaction.addLabel("processing.status", "failed");
            }
            
            return ResponseEntity.status(500)
                .body(Map.of(
                    "status", "error",
                    "error", e.getMessage(),
                    "httpRequestId", httpRequestId,
                    "timestamp", LocalDateTime.now().toString()
                ));
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Transaction transaction = ElasticApm.currentTransaction();
        if (transaction != null) {
            transaction.setName("GET /api/messages/health");
            transaction.setType("request");
            transaction.addLabel("endpoint", "health");
            transaction.addLabel("http.method", "GET");
            transaction.addLabel("business.operation", "health-check");
        }
        
        logger.info("Health check requested");
        
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "kafka-producer-java",
            "version", "1.0.0",
            "timestamp", LocalDateTime.now().toString()
        ));
    }
    
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Transaction transaction = ElasticApm.currentTransaction();
        if (transaction != null) {
            transaction.setName("GET /api/messages/info");
            transaction.setType("request");
            transaction.addLabel("endpoint", "info");
            transaction.addLabel("http.method", "GET");
            transaction.addLabel("business.operation", "service-info");
        }
        
        logger.info("Info endpoint requested");
        
        return ResponseEntity.ok(Map.of(
            "serviceName", "kafka-producer-java",
            "description", "Kafka Producer with Elastic APM",
            "version", "1.0.0",
            "kafka", Map.of(
                "defaultTopic", "cards",
                "bootstrapServers", "pkc-7xoy1.eu-central-1.aws.confluent.cloud:9092"
            ),
            "apm", Map.of(
                "enabled", true,
                "serverUrl", "http://localhost:8200",
                "environment", "local",
                "serviceName", "kafka-producer-java"
            )
        ));
    }
}