# ===============================================
# WINDOWS APM DIAGNOSTIKA
# ===============================================

Write-Host "=== APM Diagnostika ===" -ForegroundColor Green

# 1. Test APM Server
Write-Host "`n1. APM Server health check:" -ForegroundColor Yellow
try {
    $apmResponse = Invoke-RestMethod -Uri "http://localhost:8200/" -Method Get
    Write-Host "APM Server běží: $($apmResponse.build_date)" -ForegroundColor Green
} catch {
    Write-Host "APM Server nedostupný!" -ForegroundColor Red
}

# 2. Test Java aplikace
Write-Host "`n2. Java aplikace health check:" -ForegroundColor Yellow
try {
    $javaResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/messages/health" -Method Get
    Write-Host "Java aplikace běží: $($javaResponse.status)" -ForegroundColor Green
} catch {
    Write-Host "Java aplikace neběží nebo není dostupná na portu 8080!" -ForegroundColor Red
    Write-Host "Spusťte Java aplikaci pomocí: mvn spring-boot:run" -ForegroundColor Cyan
}

# 3. Zkontroluj APM data v Elasticsearch
Write-Host "`n3. APM data v Elasticsearch:" -ForegroundColor Yellow
try {
    $esResponse = Invoke-RestMethod -Uri "http://localhost:9200/apm-*/_search?size=0" -Method Get
    $totalHits = $esResponse.hits.total.value
    Write-Host "Celkem APM dokumentů: $totalHits" -ForegroundColor Green
    
    if ($totalHits -eq 0) {
        Write-Host "Žádná APM data! Aplikace neposílají data do APM." -ForegroundColor Red
    }
} catch {
    Write-Host "Chyba při dotazu na Elasticsearch!" -ForegroundColor Red
}

# 4. Test odesílání zprávy
Write-Host "`n4. Odesílám testovací zprávu..." -ForegroundColor Yellow
try {
    $body = @{
        message = "APM test message"
        topic = "cards"
        key = "test-key"
    } | ConvertTo-Json
    
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/messages/send" -Method Post -Body $body -ContentType "application/json"
    Write-Host "Zpráva odeslána: $($response.status)" -ForegroundColor Green
    
    # Počkej chvíli a zkontroluj APM data znovu
    Write-Host "Čekám 5 sekund a kontrolujem APM data..." -ForegroundColor Cyan
    Start-Sleep -Seconds 5
    
    $esResponse2 = Invoke-RestMethod -Uri "http://localhost:9200/apm-*/_search?size=0" -Method Get
    $newTotalHits = $esResponse2.hits.total.value
    Write-Host "APM dokumentů po testu: $newTotalHits" -ForegroundColor Green
    
} catch {
    Write-Host "Chyba při odesílání zprávy!" -ForegroundColor Red
}

# 5. Zkontroluj APM služby
Write-Host "`n5. APM služby:" -ForegroundColor Yellow
try {
    $query = @{
        size = 0
        aggs = @{
            services = @{
                terms = @{
                    field = "service.name.keyword"
                    size = 10
                }
            }
        }
    } | ConvertTo-Json -Depth 5
    
    $servicesResponse = Invoke-RestMethod -Uri "http://localhost:9200/apm-*/_search" -Method Post -Body $query -ContentType "application/json"
    
    if ($servicesResponse.aggregations.services.buckets.Count -gt 0) {
        Write-Host "Nalezené APM služby:" -ForegroundColor Green
        foreach ($bucket in $servicesResponse.aggregations.services.buckets) {
            Write-Host "  - $($bucket.key): $($bucket.doc_count) dokumentů" -ForegroundColor White
        }
    } else {
        Write-Host "Žádné APM služby nenalezeny!" -ForegroundColor Red
    }
} catch {
    Write-Host "Chyba při hledání služeb!" -ForegroundColor Red
}

Write-Host "`n=== Diagnostika dokončena ===" -ForegroundColor Green