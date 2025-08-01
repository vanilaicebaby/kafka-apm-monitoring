// src/main/java/com/example/kafkaapmdemo/config/ElasticApmConfig.java
package com.example.kafkaapmdemo.config;

import co.elastic.apm.attach.ElasticApmAttacher;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(value = "elastic.apm.enabled", havingValue = "true")
public class ElasticApmConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(ElasticApmConfig.class);
    
    @Value("${elastic.apm.server-url}")
    private String serverUrl;
    
    @Value("${elastic.apm.service-name}")
    private String serviceName;
    
    @Value("${elastic.apm.environment}")
    private String environment;
    
    @Value("${elastic.apm.application-packages}")
    private String applicationPackages;
    
    @Value("${elastic.apm.log-level}")
    private String logLevel;
    
    @PostConstruct
    public void init() {
        logger.info("Initializing Elastic APM for service: {}", serviceName);
        
        Map<String, String> apmProps = new HashMap<>();
        apmProps.put("server_url", serverUrl);
        apmProps.put("service_name", serviceName);
        apmProps.put("environment", environment);
        apmProps.put("application_packages", applicationPackages);
        apmProps.put("log_level", logLevel);
        
        // Kafka JMX metrics - zachytíme důležité metriky
        apmProps.put("capture_jmx_metrics", 
            // Producer metriky
            "object_name[kafka.producer:type=producer-metrics,client-id=*] " +
            "attribute[batch-size-avg:metric_name=kafka.producer.batch_size_avg]," +
            "object_name[kafka.producer:type=producer-metrics,client-id=*] " +
            "attribute[record-send-rate:metric_name=kafka.producer.record_send_rate]," +
            "object_name[kafka.producer:type=producer-metrics,client-id=*] " +
            "attribute[request-latency-avg:metric_name=kafka.producer.request_latency_avg]," +
            // Consumer metriky
            "object_name[kafka.consumer:type=consumer-metrics,client-id=*] " +
            "attribute[records-consumed-rate:metric_name=kafka.consumer.records_consumed_rate]," +
            "object_name[kafka.consumer:type=consumer-metrics,client-id=*] " +
            "attribute[fetch-latency-avg:metric_name=kafka.consumer.fetch_latency_avg]"
        );
        
        ElasticApmAttacher.attach(apmProps);
        logger.info("Elastic APM attached successfully");
    }
}