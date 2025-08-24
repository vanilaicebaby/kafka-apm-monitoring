// ElasticApmConfig.java - SIMPLIFIED bez JMX metrik
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
        
        // Základní APM konfigurace BEZ JMX metrik
        apmProps.put("capture_body", "all");
        apmProps.put("capture_headers", "true");
        apmProps.put("use_path_as_transaction_name", "true");
        apmProps.put("enable_log_correlation", "true");
        apmProps.put("trace_methods", "com.example.kafkaapmdemo.*");
        apmProps.put("trace_methods_duration_threshold", "0ms");
        
        // VYPNEME JMX metriky aby neházely chyby
        apmProps.put("capture_jmx_metrics", "");
        
        try {
            ElasticApmAttacher.attach(apmProps);
            logger.info("Elastic APM attached successfully (without JMX metrics)");
        } catch (Exception e) {
            logger.error("Failed to attach Elastic APM: {}", e.getMessage(), e);
            throw new RuntimeException("APM initialization failed", e);
        }
    }
}