// consumer.js
require('dotenv').config();

// DŮLEŽITÉ: APM musí být první import!
const apm = require('elastic-apm-node').start({
  serviceName: process.env.ELASTIC_APM_SERVICE_NAME,
  serverUrl: process.env.ELASTIC_APM_SERVER_URL,
  environment: process.env.ELASTIC_APM_ENVIRONMENT,
  logLevel: process.env.ELASTIC_APM_LOG_LEVEL,
  captureBody: 'all',
  captureHeaders: true,
  usePathAsTransactionName: true
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

async function runConsumer() {
  try {
    console.log('🚀 Starting Kafka Consumer with APM...');
    
    await consumer.connect();
    console.log('✅ Connected to Kafka');
    
    await consumer.subscribe({ 
      topic: process.env.KAFKA_TOPIC, 
      fromBeginning: false 
    });
    console.log(`✅ Subscribed to topic: ${process.env.KAFKA_TOPIC}`);
    
    await consumer.run({
      eachMessage: async ({ topic, partition, message }) => {
        // Vytvoření APM transaction pro každou zprávu
        const transaction = apm.startTransaction(`kafka-consume-${topic}`, 'messaging');
        
        try {
          const messageValue = message.value.toString();
          const messageKey = message.key ? message.key.toString() : 'no-key';
          
          console.log(`📨 Received message:`, {
            topic,
            partition,
            offset: message.offset,
            key: messageKey,
            value: messageValue,
            timestamp: new Date(parseInt(message.timestamp))
          });
          
          // APM labels pro better searchability
          transaction.setLabel('kafka.topic', topic);
          transaction.setLabel('kafka.partition', partition);
          transaction.setLabel('kafka.offset', message.offset);
          transaction.setLabel('kafka.key', messageKey);
          transaction.setLabel('message.size', messageValue.length);
          
          // Simulace zpracování zprávy
          const span = apm.startSpan('message-processing', 'business-logic');
          await processMessage(messageValue);
          if (span) span.end();
          
          // Simulace volání externí služby
          const externalSpan = apm.startSpan('external-api-call', 'http');
          await simulateExternalApiCall(messageValue);
          if (externalSpan) externalSpan.end();
          
          transaction.result = 'success';
          console.log('✅ Message processed successfully');
          
        } catch (error) {
          console.error('❌ Error processing message:', error);
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

// Simulace zpracování zprávy
async function processMessage(message) {
  // Simulace různých processing times
  const processingTime = Math.random() * 200 + 50; // 50-250ms
  await new Promise(resolve => setTimeout(resolve, processingTime));
  
  // Občas simuluj error pro testing
  if (Math.random() < 0.05) { // 5% chybovost
    throw new Error('Random processing error for testing');
  }
}

// Simulace volání externí služby
async function simulateExternalApiCall(message) {
  const span = apm.currentSpan;
  if (span) {
    span.setLabel('external.service', 'payment-api');
    span.setLabel('message.content', message.substring(0, 50));
  }
  
  // Simulace API callu
  const apiTime = Math.random() * 100 + 30; // 30-130ms
  await new Promise(resolve => setTimeout(resolve, apiTime));
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