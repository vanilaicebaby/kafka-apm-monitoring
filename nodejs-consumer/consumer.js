// consumer.js s distributed tracing support
require('dotenv').config();

// DŮLEŽITÉ: APM musí být první import!
const apm = require('elastic-apm-node').start({
  serviceName: process.env.ELASTIC_APM_SERVICE_NAME,
  serverUrl: process.env.ELASTIC_APM_SERVER_URL,
  environment: process.env.ELASTIC_APM_ENVIRONMENT,
  logLevel: process.env.ELASTIC_APM_LOG_LEVEL,
  captureBody: 'all',
  captureHeaders: true,
  usePathAsTransactionName: true,
  active: true,
  instrument: true
});

const { Kafka } = require('kafkajs');

// Kafka konfigurace pro Confluent Cloud
const kafka = new Kafka({
  clientId: 'nodejs-consumer',
  brokers: [process.env.KAFKA_BROKERS],
  ssl: true,
  sasl: {
    mechanism: 'plain',
    username: process.env.KAFKA_USERNAME,
    password: process.env.KAFKA_PASSWORD,
  },
});

const consumer = kafka.consumer({ 
  groupId: process.env.KAFKA_GROUP_ID,
  sessionTimeout: 30000,
  heartbeatInterval: 3000,
});

// Funkce pro extrakci tracing headers z Kafka zprávy
function extractTracingHeaders(message) {
  const headers = {};
  
  if (message.headers) {
    for (const [key, value] of Object.entries(message.headers)) {
      if (Buffer.isBuffer(value)) {
        headers[key] = value.toString('utf8');
      } else {
        headers[key] = value;
      }
    }
  }
  
  return headers;
}

async function runConsumer() {
  try {
    console.log('Starting Kafka Consumer with APM...');
    
    await consumer.connect();
    console.log('Connected to Kafka');
    
    await consumer.subscribe({ 
      topic: process.env.KAFKA_TOPIC, 
      fromBeginning: false 
    });
    console.log(`Subscribed to topic: ${process.env.KAFKA_TOPIC}`);
    
    await consumer.run({
      eachMessage: async ({ topic, partition, message }) => {
        // Extrahuj tracing headers z Kafka zprávy
        const tracingHeaders = extractTracingHeaders(message);
        const activityId = tracingHeaders['ActivityId'] || 'unknown';
        const requestId = tracingHeaders['RequestId'] || 'unknown';
        const transparent = tracingHeaders['Transparent'] || 'false';
        
        // Vytvoření APM transaction pro každou zprávu s distributed tracing
        const transaction = apm.startTransaction(`kafka-consume-${topic}`, 'messaging');
        
        // Pokud existují APM tracing headers, pokus se navázat na parent trace
        const apmTraceparent = tracingHeaders['apm-traceparent'];
        const apmTracestate = tracingHeaders['apm-tracestate'];
        
        if (apmTraceparent) {
          try {
            // Nastavíme parent trace context
            transaction.setTraceParent(apmTraceparent);
            if (apmTracestate) {
              transaction.tracestate = apmTracestate;
            }
          } catch (error) {
            console.warn('Failed to set trace parent:', error.message);
          }
        }
        
        try {
          const messageValue = message.value.toString();
          const messageKey = message.key ? message.key.toString() : 'no-key';
          
          console.log(`📨 Received message with tracing:`, {
            topic,
            partition,
            offset: message.offset,
            key: messageKey,
            value: messageValue.substring(0, 100), // Zkrácená verze pro log
            timestamp: new Date(parseInt(message.timestamp)),
            activityId,
            requestId,
            transparent,
            hasApmTrace: !!apmTraceparent
          });
          
          // APM labels pro better searchability
          transaction.setLabel('kafka.topic', topic);
          transaction.setLabel('kafka.partition', partition);
          transaction.setLabel('kafka.offset', message.offset);
          transaction.setLabel('kafka.key', messageKey);
          transaction.setLabel('message.size', messageValue.length);
          transaction.setLabel('activityId', activityId);
          transaction.setLabel('requestId', requestId);
          transaction.setLabel('transparent', transparent);
          transaction.setLabel('distributed.tracing', apmTraceparent ? 'enabled' : 'disabled');
          
          // Simulace zpracování zprávy s tracing context
          const processingSpan = apm.startSpan('message-processing', 'business-logic');
          if (processingSpan) {
            processingSpan.setLabel('activityId', activityId);
            processingSpan.setLabel('requestId', requestId);
            processingSpan.setLabel('message.content', messageValue.substring(0, 50));
          }
          
          await processMessage(messageValue, activityId, requestId);
          if (processingSpan) processingSpan.end();
          
          // Simulace volání externí služby s propagací tracing
          const externalSpan = apm.startSpan('external-api-call', 'http');
          if (externalSpan) {
            externalSpan.setLabel('activityId', activityId);
            externalSpan.setLabel('requestId', requestId);
            externalSpan.setLabel('external.service', 'payment-api');
            externalSpan.setLabel('service.name', 'nodejs-consumer');
          }
          
          await simulateExternalApiCall(messageValue, activityId, requestId);
          if (externalSpan) externalSpan.end();
          
          transaction.result = 'success';
          console.log(`✅ Message processed successfully - ActivityId: ${activityId}, RequestId: ${requestId}`);
          
        } catch (error) {
          console.error(`❌ Error processing message - ActivityId: ${activityId}, RequestId: ${requestId}:`, error);
          apm.captureError(error);
          transaction.result = 'error';
          throw error;
        } finally {
          transaction.end();
        }
      },
    });
    
  } catch (error) {
    console.error('❌ Consumer error:', error);
    apm.captureError(error);
    process.exit(1);
  }
}

// Simulace zpracování zprávy s tracing context
async function processMessage(message, activityId, requestId) {
  const span = apm.currentSpan;
  
  if (span) {
    span.setLabel('business.activityId', activityId);
    span.setLabel('business.requestId', requestId);
    span.setLabel('processing.type', 'message-validation');
  }
  
  // Simulace různých processing times
  const processingTime = Math.random() * 200 + 50; // 50-250ms
  await new Promise(resolve => setTimeout(resolve, processingTime));
  
  // Občas simuluj error pro testing (pokud zpráva obsahuje "error")
  if (message.toLowerCase().includes('error')) {
    throw new Error(`Simulated processing error for ActivityId: ${activityId}`);
  }
  
  console.log(`🔄 Message validated - ActivityId: ${activityId}, RequestId: ${requestId}`);
}

// Simulace volání externí služby s distributed tracing
async function simulateExternalApiCall(message, activityId, requestId) {
  const span = apm.currentSpan;
  
  if (span) {
    span.setLabel('external.service', 'payment-api');
    span.setLabel('external.activityId', activityId);
    span.setLabel('external.requestId', requestId);
    span.setLabel('message.content', message.substring(0, 50));
    span.setLabel('api.version', '1.0');
  }
  
  // Simulace API callu
  const apiTime = Math.random() * 100 + 30; // 30-130ms
  await new Promise(resolve => setTimeout(resolve, apiTime));
  
  console.log(`🌐 External API called - ActivityId: ${activityId}, RequestId: ${requestId}`);
}

// Graceful shutdown
process.on('SIGTERM', async () => {
  console.log('🛑 Shutting down consumer...');
  await consumer.disconnect();
  process.exit(0);
});

process.on('SIGINT', async () => {
  console.log('🛑 Shutting down consumer...');
  await consumer.disconnect();
  process.exit(0);
});

// Start consumer
runConsumer().catch(error => {
  console.error('❌ Failed to start consumer:', error);
  apm.captureError(error);
  process.exit(1);
});