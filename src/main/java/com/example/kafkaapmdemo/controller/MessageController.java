// MessageController.java - s APM tracing
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
        // Explicit APM transaction pro HTTP request
        Transaction transaction = ElasticApm.currentTransaction();
        if (transaction == null) {
            transaction = ElasticApm.startTransaction();
            transaction.setName("POST /api/messages/send");
            transaction.setType("request");
        }
        
        String requestId = UUID.randomUUID().toString();
        
        try {
            String message = request.get("message");
            String topic = request.getOrDefault("topic", "cards");
            String key = request.getOrDefault("key", "default-key");
            
            logger.info("Received HTTP request to send message - RequestId: {}, Topic: {}, Message: {}", 
                requestId, topic, message);
            
            // Nastav transaction labels
            transaction.setLabel("http.method", "POST");
            transaction.setLabel("http.url", "/api/messages/send");
            transaction.setLabel("requestId", requestId);
            transaction.setLabel("kafka.topic", topic);
            transaction.setLabel("kafka.key", key);
            transaction.setLabel("message.size", message != null ? message.length() : 0);
            
            if (message == null || message.trim().isEmpty()) {
                transaction.setResult("error");
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message cannot be empty", "requestId", requestId));
            }
            
            // Zavolej producer service (který vytvoří vlastní span)
            producerService.sendMessage(topic, key, message);
            
            transaction.setResult("success");
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Message sent to topic: " + topic,
                "key", key,
                "requestId", requestId,
                "timestamp", LocalDateTime.now().toString()
            ));
            
        } catch (Exception e) {
            logger.error("Error in sendMessage endpoint - RequestId: {}, Error: {}", 
                requestId, e.getMessage(), e);
            
            transaction.captureException(e);
            transaction.setResult("error");
            
            return ResponseEntity.status(500)
                .body(Map.of(
                    "status", "error",
                    "error", e.getMessage(),
                    "requestId", requestId,
                    "timestamp", LocalDateTime.now().toString()
                ));
        } finally {
            transaction.end();
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Transaction transaction = ElasticApm.currentTransaction();
        if (transaction != null) {
            transaction.setName("GET /api/messages/health");
            transaction.setType("request");
            transaction.setLabel("http.method", "GET");
            transaction.setLabel("http.url", "/api/messages/health");
        }
        
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
        }
        
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