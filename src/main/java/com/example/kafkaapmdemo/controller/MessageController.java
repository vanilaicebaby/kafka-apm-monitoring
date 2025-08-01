// src/main/java/com/example/kafkaapmdemo/controller/MessageController.java
package com.example.kafkaapmdemo.controller;

import com.example.kafkaapmdemo.service.MessageProducerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    
    @Autowired
    private MessageProducerService producerService;
    
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, String> request) {
        try {
            String message = request.get("message");
            String topic = request.getOrDefault("topic", "cards");
            String key = request.getOrDefault("key", "default-key");
            
            if (message == null || message.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Message cannot be empty"));
            }
            
            producerService.sendMessage(topic, key, message);
            
            return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Message sent to topic: " + topic,
                "timestamp", LocalDateTime.now().toString()
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(500)
                .body(Map.of(
                    "status", "error",
                    "error", e.getMessage(),
                    "timestamp", LocalDateTime.now().toString()
                ));
        }
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "kafka-demo-app",
            "timestamp", LocalDateTime.now().toString()
        ));
    }
}