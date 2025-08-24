#!/bin/bash

echo "=== Oprava APM indexů - nastavení replik na 0 ==="

# 1. Nastavíme repliky na 0 pro všechny existující APM indexy
echo "1. Nastavuji repliky na 0 pro existující APM indexy..."
curl -X PUT "localhost:9200/apm-7.17.15-*/_settings" -H 'Content-Type: application/json' -d'
{
  "index": {
    "number_of_replicas": 0
  }
}'

# 2. Vytvoříme template pro budoucí APM indexy
echo -e "\n2. Vytvářím template pro budoucí APM indexy..."
curl -X PUT "localhost:9200/_index_template/apm-template" -H 'Content-Type: application/json' -d'
{
  "index_patterns": ["apm-*"],
  "priority": 100,
  "template": {
    "settings": {
      "number_of_replicas": 0,
      "number_of_shards": 1
    }
  }
}'

# 3. Zkontrolujeme stav po změně
echo -e "\n3. Čekám 10 sekund na aplikaci změn..."
sleep 10

echo -e "\n4. Kontrola stavu indexů po změně:"
curl -s "localhost:9200/_cat/indices/apm-*?v&s=index&h=health,status,index,pri,rep,docs.count"

# 5. Cluster health
echo -e "\n5. Celkový stav clusteru:"
curl -s "localhost:9200/_cluster/health?pretty" | jq -r '"Status: " + .status + ", Active shards: " + (.active_shards|tostring) + ", Unassigned: " + (.unassigned_shards|tostring)'

echo -e "\n=== Oprava dokončena ==="