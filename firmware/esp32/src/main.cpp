#include "DeviceState.h"
static QueueHandle_t bleQueue;
static std::atomic<bool> usbStreaming{false};
static std::atomic<uint32_t> usbStreamRequestedAt{0};
void usbStreamControl(bool enabled, uint32_t now) {
  usbStreamRequestedAt = now;
  usbStreaming = enabled;
}
bool usbStreamActive() {
  return usbStreaming && millis() - usbStreamRequestedAt.load() < 15000;
}
struct BlePacket {
  char json[2048];
  uint16_t id;
  bool pulseCapture = false;
};
bool queuePulseCapture(JsonDocument& doc) {
  BlePacket packet;
  packet.pulseCapture = true;
  packet.id = 0;
  if (measureJson(doc) >= sizeof(packet.json)) return false;
  serializeJson(doc, packet.json, sizeof(packet.json));
  return bleQueue && xQueueSend(bleQueue, &packet, 0) == pdTRUE;
}
static void bleWorker(void*) {
  BlePacket p;
  for (;;)
    if (xQueueReceive(bleQueue, &p, portMAX_DELAY) == pdTRUE) {
      if (p.pulseCapture) {
        // Explicit serial bench capture only, never radio telemetry. UART output
        // stays off the sampling core even for a full 100-pair waveform.
        Serial.printf("PPG %s\n", p.json);
        continue;
      }
      // UART output runs in the delivery worker, away from the sensor sampling loop.
      if (usbStreamActive()) Serial.printf("TELEMETRY %s\n", p.json);
      blePublish(p.json, p.id);
    }
}
void setup() {
  Serial.begin(115200);
  sensorsBegin();
  transportBegin();
  clockBegin();
  displayBegin();
  bleBegin();
  bleQueue = xQueueCreate(2, sizeof(BlePacket));
  if (!bleQueue) ESP.restart();
  xTaskCreatePinnedToCore(bleWorker, "ble-delivery", 8192, nullptr, 1, nullptr, 0);
  Serial.println(
      "SmartTrack 1.1.1-dev ready. Use serial JSON to provision; measurements are experimental.");
}
void loop() {
  static uint32_t sequence = 0, lastTelemetry = 0;
  uint32_t now = millis();
  sensorsTick(now);
  transportTick(now);
  clockTick();
  displayTick(now);
  if (now - lastTelemetry >= deliveryIntervalMs) {
    lastTelemetry = now;
    JsonDocument doc;
    serializeTelemetry(doc, sequence, now);
    BlePacket packet;
    packet.id = sequence++;
    size_t needed = measureJson(doc);
    if (needed < sizeof(packet.json)) {
      serializeJson(doc, packet.json, sizeof(packet.json));
      queueTelemetry(packet.json);
      xQueueSend(bleQueue, &packet, 0);
      state.fallEvent = false;
      state.impactEvent = false;
    } else
      Serial.println("Telemetry exceeds bounded packet size");
  }
  delay(1);
}
