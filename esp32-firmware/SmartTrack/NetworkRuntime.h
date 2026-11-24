#pragma once

// Wi-Fi/HTTP are isolated from optical sampling, sensor acquisition and OLED transfers.
// Signed discovery avoids reflashing when the laptop's DHCP address changes.
void networkWorker(void*) {
  WiFi.persistent(false);WiFi.mode(WIFI_STA);WiFi.setAutoReconnect(true);
  WiFiUDP discovery;discovery.begin(SmartTrackDiscoveryPort);
  if(strlen(WIFI_SSID))WiFi.begin(WIFI_SSID,WIFI_PASSWORD);
  else Serial.println("WIFI NOT CONFIGURED; run CONFIGURE_WIFI.cmd");
  Packet packet;bool pending=false;String server;
  uint32_t lastReconnect=0,lastDiscovery=0,lastConfig=0,lastDelivered=0,lastRequest=0,retryMs=0;
  char nonce[17]={};
  while(true) {
    uint32_t now=millis();wifiConnected=WiFi.status()==WL_CONNECTED;
    if(uint32_t(now-lastDelivered)>10000)backendOK=false;
    if(!wifiConnected) {
      backendOK=false;server="";
      if(strlen(WIFI_SSID) && uint32_t(now-lastReconnect)>=10000){WiFi.reconnect();lastReconnect=now;}
      vTaskDelay(pdMS_TO_TICKS(100));continue;
    }
    if(server.length()==0 && uint32_t(now-lastDiscovery)>=3000) {
      snprintf(nonce,sizeof(nonce),"%08lx%08lx",(unsigned long)esp_random(),(unsigned long)esp_random());
      JsonDocument request;request["type"]="smarttrack-discover";request["deviceId"]=deviceId;request["nonce"]=nonce;
      char bytes[256];size_t size=serializeJson(request,bytes,sizeof(bytes));
      discovery.beginPacket(IPAddress(255,255,255,255),SmartTrackDiscoveryPort);
      discovery.write(reinterpret_cast<uint8_t*>(bytes),size);discovery.endPacket();lastDiscovery=now;
    }
    for(uint8_t budget=0;budget<4 && discovery.parsePacket();++budget) {
      IPAddress source=discovery.remoteIP();char bytes[512];int size=discovery.read(bytes,sizeof(bytes)-1);
      if(size<=0)continue;bytes[size]=0;JsonDocument response;
      if(deserializeJson(response,bytes) || strcmp(response["nonce"]|"",nonce) || !nonce[0])continue;
      uint16_t port=response["port"]|0;if(!port)continue;
      String body="DISCOVER|"+deviceId+"|"+nonce+"|"+String(port);
      unsigned char digest[32];char expected[65];
      const auto* algorithm=mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
      if(mbedtls_md_hmac(algorithm,reinterpret_cast<const unsigned char*>(DEVICE_KEY),strlen(DEVICE_KEY),reinterpret_cast<const unsigned char*>(body.c_str()),body.length(),digest))continue;
      for(uint8_t i=0;i<32;++i)snprintf(expected+i*2,3,"%02x",digest[i]);
      const char* signature=response["signature"]|"";uint8_t mismatch=strlen(signature)==64?0:1;
      if(strlen(signature)==64)for(uint8_t i=0;i<64;++i)mismatch|=uint8_t(expected[i]^signature[i]);
      if(mismatch)continue;
      server="http://"+source.toString()+":"+String(port);lastConfig=now-5000;
      Serial.println("WIFI SERVER DISCOVERED (authenticated)");
    }
    if(server.length()==0){vTaskDelay(pdMS_TO_TICKS(50));continue;}
    if(!pending)pending=xQueueReceive(networkQueue,&packet,0)==pdTRUE;
    if(pending && uint32_t(now-packet.created)>5000)pending=false;
    bool configDue=uint32_t(now-lastConfig)>=5000;
    if(!pending && !configDue){vTaskDelay(pdMS_TO_TICKS(30));continue;}
    if(retryMs && uint32_t(now-lastRequest)<retryMs){vTaskDelay(pdMS_TO_TICKS(20));continue;}
    WiFiClient client;HTTPClient http;http.setConnectTimeout(1800);http.setTimeout(1800);
    String path=configDue?"/api/device-config/":"/api/ingest/";
    int status=-1;
    if(http.begin(client,server+path+deviceId)) {
      http.addHeader("X-Device-Key",DEVICE_KEY);
      if(configDue) {
        lastConfig=now;status=http.GET();
        if(status==200) {
          JsonDocument config;
          if(!deserializeJson(config,http.getString())) {
            Commands command={config["sensitivity"]|MOTION_SENSITIVITY,config["impactG"]|IMPACT_THRESHOLD_G,config["lowG"]|LOW_G_THRESHOLD,config["inactivityMs"]|FALL_INACTIVITY_MS,config["cooldownMs"]|FALL_COOLDOWN_MS,config["cancelVersion"]|0ULL,config["calibrateVersion"]|0ULL};
            xQueueOverwrite(commandQueue,&command);
            int64_t epoch=config["serverEpoch"]|0LL;
            if(epoch>=1700000000LL && epoch<4102444800LL){timeval clock={time_t(epoch),0};settimeofday(&clock,nullptr);}
          }
        }
      } else {
        http.addHeader("Content-Type","application/json");
        status=http.POST(reinterpret_cast<uint8_t*>(packet.json),strlen(packet.json));
        if(status>=200 && status<300){pending=false;backendOK=true;lastDelivered=millis();}
        else {backendOK=false;if(status==400)pending=false;}
      }
      http.end();
    }
    lastRequest=millis();
    // Bound request traffic during outages or a rejected device key. Never delay acquisition.
    if(status<200 || status>=300) retryMs=status==401?10000:min(retryMs?retryMs*2:500UL,8000UL);
    else retryMs=0;
    if(status<=0 || status>=500){server="";backendOK=false;}
    if(status==401)Serial.println("WIFI DEVICE KEY REJECTED; check private setup");
    vTaskDelay(pdMS_TO_TICKS(50));
  }
}
