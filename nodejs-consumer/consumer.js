require('dotenv').config();

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
        const tracingHeaders = extractTracingHeaders(message);
        const activityId = tracingHeaders['ActivityId'] || 'unknown';
        const requestId = tracingHeaders['RequestId'] || 'unknown';
        const transparent = tracingHeaders['Transparent'] || 'false';
        const sourceService = tracingHeaders['X-Source-Service'] || 'unknown';
        
        const transaction = apm.startTransaction(`kafka-consume-${topic}`, 'messaging');
        
        const apmTraceparent = tracingHeaders['apm-traceparent'];
        const apmTracestate = tracingHeaders['apm-tracestate'];
        
        if (apmTraceparent) {
          try {
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
          const consumeId = Date.now() + '-' + Math.random().toString(36).substr(2, 9);
          
          console.log(`Received message with tracing:`, {
            topic,
            partition,
            offset: message.offset,
            key: messageKey,
            value: messageValue.substring(0, 100), // Zkrácená verze pro log, pojeabne to nefunguje pres 100? proc sakra, 2 hodizn jsem to resil...
            timestamp: new Date(parseInt(message.timestamp)),
            activityId,
            requestId,
            transparent,
            sourceService,
            hasDistributedTrace: !!apmTraceparent,
            consumeId
          });
          
          transaction.setLabel('kafka.topic', topic);
          transaction.setLabel('kafka.partition', partition.toString());
          transaction.setLabel('kafka.offset', message.offset.toString());
          transaction.setLabel('kafka.key', messageKey);
          transaction.setLabel('message.size', messageValue.length.toString());
          
          transaction.setLabel('activityId', activityId);
          transaction.setLabel('requestId', requestId);
          transaction.setLabel('transparent', transparent);
          transaction.setLabel('consume.id', consumeId);
          
          transaction.setLabel('source.service', sourceService);
          transaction.setLabel('target.service', 'nodejs-consumer');
          transaction.setLabel('distributed.trace.connected', apmTraceparent ? 'true' : 'false');
          transaction.setLabel('business.operation', 'kafka-consume');
          
          const processingSpan = apm.startSpan('message-processing', 'business-logic');
          if (processingSpan) {
            processingSpan.setLabel('activityId', activityId);
            processingSpan.setLabel('requestId', requestId);
            processingSpan.setLabel('processing.type', 'message-validation');
            processingSpan.setLabel('message.content', messageValue.substring(0, 50));
            processingSpan.setLabel('business.step', 'validation');
          }
          
          await processMessage(messageValue, activityId, requestId);
          if (processingSpan) processingSpan.end();
          
          const externalSpan = apm.startSpan('external-api-call', 'http');
          if (externalSpan) {
            externalSpan.setLabel('activityId', activityId);
            externalSpan.setLabel('requestId', requestId);
            externalSpan.setLabel('external.service', 'payment-api');
            externalSpan.setLabel('external.endpoint', '/api/payments/validate');
            externalSpan.setLabel('api.version', '1.0');
            externalSpan.setLabel('business.step', 'external-validation');
          }
          
          await simulateExternalApiCall(messageValue, activityId, requestId);
          if (externalSpan) externalSpan.end();
          
          transaction.result = 'success';
          console.log(`Message processed successfully - ActivityId: ${activityId}, RequestId: ${requestId}, ConsumeId: ${consumeId}`);
          
        } catch (error) {
          console.error(`Error processing message - ActivityId: ${activityId}, RequestId: ${requestId}:`, error);
          apm.captureError(error, {
            custom: {
              activityId,
              requestId,
              topic,
              partition,
              offset: message.offset
            }
          });
          transaction.result = 'error';
          transaction.setLabel('error.type', error.name || 'UnknownError');
          transaction.setLabel('error.message', error.message.substring(0, 200));
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
    span.setLabel('processing.status', 'in-progress');
  }
  
  // Simulace různých processing times
  const processingTime = Math.random() * 200 + 50; // 50-250ms
  await new Promise(resolve => setTimeout(resolve, processingTime));
  
  if (message.toLowerCase().includes('error')) {
    const error = new Error(`Simulated processing error for ActivityId: ${activityId}`);
    if (span) {
      span.setLabel('processing.status', 'failed');
      span.setLabel('error.simulated', 'true');
    }
    throw error;
  }
  
  if (span) {
    span.setLabel('processing.status', 'completed');
  }
  
  console.log(`Message validated - ActivityId: ${activityId}, RequestId: ${requestId}`);
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
    span.setLabel('api.call.status', 'in-progress');
  }
  
  // Simulace API callu
  const apiTime = Math.random() * 100 + 30; // 30-130ms
  await new Promise(resolve => setTimeout(resolve, apiTime));
  
  if (span) {
    span.setLabel('api.call.status', 'completed');
    span.setLabel('api.response.code', '200');
  }
  
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