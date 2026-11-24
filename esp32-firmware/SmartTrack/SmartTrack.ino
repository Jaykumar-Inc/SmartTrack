
/*
  SMARTTRACK WIFI WATCH - existing acquisition and sensor contract retained
  ESP32 DevKit V1

  MAX30102     : Heart rate / SpO2
  GY-LSM6DS3   : Motion / Gyroscope / internal chip temperature
  SSD1306 OLED : Live sensor display

  I2C SDA: GPIO 21
  I2C SCL: GPIO 22
  TTP223 OUT1 -> GPIO32, OUT2 -> GPIO33; VCC 3.3V, common GND

  Serial Monitor: 115200 baud

  Educational prototype only.
*/

#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include <atomic>

#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>

#include "SparkFunLSM6DS3.h"
#include "MAX30105.h"
#include "spo2_algorithm.h"

#include "TemperatureSample.h"
#include <WiFi.h>
#include <WiFiUdp.h>
#include <HTTPClient.h>
#include <mbedtls/md.h>
#include <esp_arduino_version.h>
#include <ArduinoJson.h>
#include <time.h>
#include <sys/time.h>
#include "MotionEngine.h"
#include "OledFrameTransfer.h"
#if __has_include("smarttrack_config.h")
#include "smarttrack_config.h"
#else
#include "config.example.h"
#endif
#include "WatchConfig.h"
#include "WatchState.h"
#include "WiFiConfig.h"


// ================= PINS =================

#define I2C_SDA 21
#define I2C_SCL 22


#define OLED_ADDR 0x3C
#define MAX_ADDR 0x57
#define IMU_ADDR 0x6B

#define SCREEN_WIDTH 128
#ifndef OLED_HEIGHT
#define OLED_HEIGHT 64
#endif
static_assert(OLED_HEIGHT==64||OLED_HEIGHT==32,"Supported SSD1306 sizes: 128x64 or 128x32");
#define SCREEN_HEIGHT OLED_HEIGHT

// ================= OBJECTS =================

Adafruit_SSD1306 oled(
  SCREEN_WIDTH,
  SCREEN_HEIGHT,
  &Wire,
  -1
);

LSM6DS3 imu(I2C_MODE, 0x6B);
MAX30105 maxSensor;

TemperatureSample chipTemperature;

// ================= STATUS =================

bool oledOK = false;
bool imuOK = false;
bool maxOK = false;

bool fingerDetected = false;
bool possibleImpact = false;

// ================= MAX30102 =================

// FIX: Do not declare BUFFER_SIZE.
// spo2_algorithm.h already defines that macro.

const int SPO2_SAMPLE_COUNT = 100;
const int SPO2_WINDOW_SHIFT = 25;

uint32_t irBuffer[SPO2_SAMPLE_COUNT];
uint32_t redBuffer[SPO2_SAMPLE_COUNT];

int sampleIndex = 0;

uint32_t irValue = 0;

int32_t heartRate = 0;
int32_t oxygenLevel = 0;

int8_t validHR = 0;
int8_t validSpO2 = 0;

MotionEngine motion;
SemaphoreHandle_t busMutex;
struct Packet { char json[3072]; uint32_t created; };
struct Commands {float sensitivity,impactG,lowG; uint32_t inactivityMs,cooldownMs; uint64_t cancelVersion,calibrateVersion;};
QueueHandle_t networkQueue,displayQueue,commandQueue,serialQueue,watchMotionQueue;
String deviceId,bootId;uint64_t sequenceNo=0;
std::atomic<bool> wifiConnected{false},backendOK{false};
uint32_t lastTemperature=0,lastOpticalSample=0;
bool opticalQuality=false;const char* quality="UNAVAILABLE";
float bpmAverage=0,spoAverage=0;int bpmAverageCount=0,spoAverageCount=0;
uint64_t cancelVersion=0,calibrateVersion=0;bool configReceived=false;
String utc(time_t epoch){if(epoch<1700000000)return "";tm t;gmtime_r(&epoch,&t);char out[32];strftime(out,sizeof(out),"%Y-%m-%dT%H:%M:%SZ",&t);return String(out);}
bool lockBus(){return xSemaphoreTake(busMutex,pdMS_TO_TICKS(4))==pdTRUE;}
void unlockBus(){xSemaphoreGive(busMutex);}


// ================= MOTION =================

float ax = 0;
float ay = 0;
float az = 0;

float gx = 0;
float gy = 0;
float gz = 0;

float pitch = 0;
float roll = 0;

float accelerationG = 0;


unsigned long impactTime = 0;

// Experimental threshold, not reliable fall detection.
// Thresholds come from config.example.h and authenticated device configuration.

// ================= TIMERS =================

unsigned long lastMotion = 0;
unsigned long lastSerial = 0;
unsigned long lastRecovery = 0;

const unsigned long MOTION_INTERVAL = 20;
const unsigned long SERIAL_INTERVAL = 1000;
const unsigned long RECOVERY_INTERVAL = 3000;

// ================= I2C =================

bool i2cPresent(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

void scanI2C() {
  Serial.println();
  Serial.println("===== EXPECTED I2C DEVICES =====");

  int count = 0;

  // Probe only the three wired modules; a stuck bus must not delay startup across 126 addresses.
  for (uint8_t address : {uint8_t(OLED_ADDR),uint8_t(MAX_ADDR),uint8_t(IMU_ADDR)}) {
    if (i2cPresent(address)) {
      Serial.printf("Device: 0x%02X\n", address);
      count++;
    }
  }

  Serial.printf("Devices found: %d\n", count);
}

// ================= INITIALIZE OLED =================

void initOLED() {
  if (oledOK) return;

  if (!i2cPresent(OLED_ADDR)) {
    Serial.println("OLED NOT FOUND");
    return;
  }

  oledOK = oled.begin(
    SSD1306_SWITCHCAPVCC,
    OLED_ADDR
  );

  if (oledOK) {
    oled.clearDisplay();
    oled.setTextSize(1);
    oled.setTextColor(SSD1306_WHITE);
    oled.setCursor(0, 0);

    oled.println("SMARTTRACK WATCH");
    oled.println("----------------");
    oled.println("Starting sensors...");
    // The display worker sends the first frame in bounded, acknowledged chunks.

    Serial.println("OLED CONNECTED");
  }
}

// ================= INITIALIZE LSM6DS3 =================

void initIMU() {
  if (imuOK) return;

  if (!i2cPresent(IMU_ADDR)) {
    Serial.println("LSM6DS3 NOT FOUND");
    return;
  }

  imu.settings.accelRange=8;
  imu.settings.accelSampleRate=104;
  imu.settings.gyroRange=500;
  imu.settings.gyroSampleRate=104;
  int result = imu.begin();

  imuOK = (result == 0);

  if (imuOK) {
    motion.beginCalibration();
    Serial.println("LSM6DS3 CONNECTED at 0x6B; keep board still, flat, +Z up for 3 seconds");
  } else {
    Serial.printf(
      "LSM6DS3 INITIALIZATION FAILED: %d\n",
      result
    );
  }
}

// ================= INITIALIZE MAX30102 =================

void initMAX() {
  if (maxOK) return;

  if (!i2cPresent(MAX_ADDR)) {
    Serial.println("MAX30102 NOT FOUND");
    return;
  }

  maxOK = maxSensor.begin(
    Wire,
    I2C_SPEED_STANDARD
  );

  if (maxOK) {
    maxSensor.setup(
      60,    // LED brightness
      4,     // Sample averaging
      2,     // Red + IR
      100,   // Sample rate
      411,   // Pulse width
      4096   // ADC range
    );

    maxSensor.setPulseAmplitudeRed(0x3C);
    maxSensor.setPulseAmplitudeIR(0x3C);
    maxSensor.setPulseAmplitudeGreen(0);
    // Fresh hardware warming its optical window is poor/settling quality, not disconnected.
    quality="POOR";

    Serial.println("MAX30102 CONNECTED");
  } else {
    Serial.println("MAX30102 INITIALIZATION FAILED");
  }
}

// ================= CHIP TEMPERATURE =================
// Short checked I2C read, scheduled separately from calibration and network I/O.
void updateTemperature() {
  if(!imuOK) { chipTemperature.invalidate(); return; }
  if(!lockBus()) return; // Freshness expires naturally if the bus remains busy.
  uint8_t status=0;
  bool ok=imu.readRegister(&status,0x1E)==0;
  if(ok && (status & 0x04)) {
    Wire.beginTransmission(IMU_ADDR); Wire.write(0x20);
    ok=Wire.endTransmission(false)==0;
    if(ok) ok=Wire.requestFrom(uint8_t(IMU_ADDR),uint8_t(2))==2;
    if(ok) {
      uint16_t low=Wire.read(), high=Wire.read();
      chipTemperature.accept(int16_t(low | (high<<8)),millis());
    }
  }
  if(!ok) chipTemperature.invalidate();
  unlockBus();
}

// ================= MOTION =================
void updateMotion(){
  if(!imuOK||!lockBus())return;
  uint8_t identity=0;
  if(imu.readRegister(&identity,0x0F)!=0||identity!=0x69){imuOK=false;motion.cancel(millis());unlockBus();return;}
  MotionSample sample={imu.readFloatAccelX(),imu.readFloatAccelY(),imu.readFloatAccelZ(),imu.readFloatGyroX(),imu.readFloatGyroY(),imu.readFloatGyroZ()};
  unlockBus();
  smartwatch::WatchMotionSample wakeSample={sample.ax,sample.ay,sample.az,sample.gx,sample.gy,sample.gz,millis()};
  xQueueOverwrite(watchMotionQueue,&wakeSample);
  motion.update(sample,millis());
  ax=motion.ax;ay=motion.ay;az=motion.az;gx=motion.gx;gy=motion.gy;gz=motion.gz;
  pitch=motion.pitch;roll=motion.roll;accelerationG=motion.magnitude;possibleImpact=motion.impact;
}
void applyCommands(){Commands c;if(xQueueReceive(commandQueue,&c,0)==pdTRUE){
  motion.config.sensitivity=c.sensitivity;motion.config.impactG=c.impactG;motion.config.lowG=c.lowG;
  motion.config.inactivityMs=c.inactivityMs;motion.config.cooldownMs=c.cooldownMs;
 if(c.cancelVersion>cancelVersion)motion.cancel(millis());if(c.calibrateVersion>calibrateVersion){motion.cancel(millis());motion.beginCalibration();}
  cancelVersion=c.cancelVersion;calibrateVersion=c.calibrateVersion;configReceived=true;
 }
 while(Serial.available()){char command=Serial.read();if(command=='c'||command=='C'){motion.cancel(millis());Serial.println("Fall event cancelled locally");}if(command=='k'||command=='K'){motion.cancel(millis());motion.beginCalibration();Serial.println("Calibration requested; keep still flat +Z up");}}
}


// ================= MAX30102 =================

void updateHeartSensor() {
  if (!maxOK) return;

  if(!lockBus())return;
  maxSensor.check();

  int processed = 0;

  while (maxSensor.available() && processed < 16) {

    uint32_t red = maxSensor.getFIFORed();
    uint32_t ir = maxSensor.getFIFOIR();

    maxSensor.nextSample();
    processed++;

    irValue = ir;
    lastOpticalSample=millis();

    fingerDetected = (irValue > FINGER_THRESHOLD);

    if (!fingerDetected) {
      quality="NO_FINGER";opticalQuality=false;bpmAverageCount=spoAverageCount=0;
      sampleIndex = 0;
      validHR = 0;
      validSpO2 = 0;
      continue;
    }

    if (sampleIndex < SPO2_SAMPLE_COUNT) {
      redBuffer[sampleIndex] = red;
      irBuffer[sampleIndex] = ir;
      sampleIndex++;
    }

    if (sampleIndex == SPO2_SAMPLE_COUNT) {

      maxim_heart_rate_and_oxygen_saturation(
        irBuffer,
        SPO2_SAMPLE_COUNT,
        redBuffer,
        &oxygenLevel,
        &validSpO2,
        &heartRate,
        &validHR
      );

      uint32_t minIR=UINT32_MAX,maxIR=0;uint64_t total=0;bool clipped=false;
      for(int i=0;i<SPO2_SAMPLE_COUNT;i++){minIR=min(minIR,irBuffer[i]);maxIR=max(maxIR,irBuffer[i]);total+=irBuffer[i];if(irBuffer[i]>=260000||redBuffer[i]>=260000)clipped=true;}
      float variation=(maxIR-minIR)/(float)max((uint32_t)(total/SPO2_SAMPLE_COUNT),(uint32_t)1);
      opticalQuality=fingerDetected&&!clipped&&variation>0.002f&&variation<0.30f&&(!imuOK||!motion.calibrated||fabsf(motion.rawMagnitude-1)<0.2f);
      quality=opticalQuality?"GOOD":"POOR";
      if(!opticalQuality){validHR=validSpO2=0;bpmAverageCount=spoAverageCount=0;}
      if(validHR&&heartRate>=30&&heartRate<=220){bpmAverage=bpmAverageCount?0.7f*bpmAverage+0.3f*heartRate:heartRate;bpmAverageCount++;}else bpmAverageCount=0;
      if(validSpO2&&oxygenLevel>=70&&oxygenLevel<=100){spoAverage=spoAverageCount?0.7f*spoAverage+0.3f*oxygenLevel:oxygenLevel;spoAverageCount++;}else spoAverageCount=0;
      // Preserve the working 100-sample / 25-sample sliding window.
      for (int i = 0;
           i < SPO2_SAMPLE_COUNT - SPO2_WINDOW_SHIFT;
           i++) {

        redBuffer[i] =
          redBuffer[i + SPO2_WINDOW_SHIFT];

        irBuffer[i] =
          irBuffer[i + SPO2_WINDOW_SHIFT];
      }

      sampleIndex =
        SPO2_SAMPLE_COUNT - SPO2_WINDOW_SHIFT;
    }
  }
  unlockBus();
}

bool bpmValid() {
  return maxOK && opticalQuality && millis()-lastOpticalSample<2000 &&
         fingerDetected &&
         validHR &&
         heartRate >= 30 &&
         heartRate <= 220;
}

bool oxygenValid() {
  return maxOK && opticalQuality && millis()-lastOpticalSample<2000 &&
         fingerDetected &&
         validSpO2 &&
         oxygenLevel >= 70 &&
         oxygenLevel <= 100;
}

// ================= WATCH DISPLAY AND TOUCH WORKER =================
#include "WatchRuntime.h"

// ================= SERIAL OUTPUT =================

void printSerial() {
  Serial.println();
  Serial.println("========== SMARTTRACK V4 ==========");

  Serial.printf(
    "Uptime: %lu seconds\n",
    (unsigned long)(millis() / 1000)
  );

  Serial.println("\n[HEALTH]");

  if (maxOK) {
    Serial.printf(
      "Finger: %s\n",
      fingerDetected ? "YES" : "NO"
    );

    Serial.printf(
      "IR: %lu\n",
      (unsigned long)irValue
    );

    if (bpmValid())
      Serial.printf(
        "Heart Rate: %ld BPM\n",
        (long)heartRate
      );
    else
      Serial.println("Heart Rate: --");

    if (oxygenValid())
      Serial.printf(
        "SpO2: %ld %%\n",
        (long)oxygenLevel
      );
    else
      Serial.println("SpO2: --");
  } else {
    Serial.println("MAX30102 unavailable");
  }

  Serial.println("\n[MOTION]");

  if (imuOK) {
    Serial.printf(
      "Acceleration: %.3f g\n",
      accelerationG
    );

    Serial.printf(
      "Accel XYZ: %.3f %.3f %.3f\n",
      ax, ay, az
    );

    Serial.printf(
      "Gyro XYZ: %.2f %.2f %.2f deg/s\n",
      gx, gy, gz
    );

    Serial.printf(
      "Pitch: %.2f Roll: %.2f\n",
      pitch, roll
    );

    if(chipTemperature.valid(millis())) Serial.printf("Chip temperature: %.2f C\n",chipTemperature.celsius);
    else Serial.println("Chip temperature unavailable");

    Serial.printf(
      "Impact: %s\n",
      possibleImpact ? "POSSIBLE" : "NO"
    );
  } else {
    Serial.println("LSM6DS3 unavailable");
  }

  Serial.printf("Fall state: %s event=%lu calibration=%s\n",fallName(motion.state),(unsigned long)motion.eventId,motion.calibration);
  Serial.println("\n[SYSTEM]");

  Serial.printf(
    "OLED: %s\n",
    oledOK ? "OK" : "FAIL"
  );

  Serial.printf(
    "LSM6DS3: %s\n",
    imuOK ? "OK" : "FAIL"
  );

  Serial.printf(
    "MAX30102: %s\n",
    maxOK ? "OK" : "FAIL"
  );

  Serial.println("==================================");
}

// ================= JSON OUTPUT =================
void printJSON(){
 JsonDocument d;d["deviceId"]=deviceId;d["bootId"]=bootId;d["sequenceNo"]=sequenceNo++;d["uptimeMs"]=millis();d["simulated"]=false;
 String captured=utc(time(nullptr));if(captured.length())d["capturedAt"]=captured;else d["capturedAt"]=nullptr;
 auto h=d["health"].to<JsonObject>();h["fingerPresent"]=maxOK&&fingerDetected&&millis()-lastOpticalSample<2000;h["quality"]=!maxOK||millis()-lastOpticalSample>=2000?"UNAVAILABLE":quality;h["valid"]=bpmValid()||oxygenValid();h["bpm"]=nullptr;h["spo2"]=nullptr;if(bpmValid())h["bpm"]=bpmAverage;if(oxygenValid())h["spo2"]=spoAverage;
 auto m=d["motion"].to<JsonObject>();bool valid=imuOK&&motion.calibrated;
 for(const char* f:{"ax","ay","az","gx","gy","gz","tilt","pitch","roll","magnitude"})m[f]=nullptr;
 if(valid){m["ax"]=ax;m["ay"]=ay;m["az"]=az;m["gx"]=gx;m["gy"]=gy;m["gz"]=gz;m["pitch"]=pitch;m["roll"]=roll;m["magnitude"]=accelerationG;m["tilt"]=acosf(constrain(az/max(accelerationG,0.001f),-1.0f,1.0f))*57.2957795f;}
 m["quality"]=!imuOK?"UNAVAILABLE":motion.calibrated?"GOOD":"CALIBRATING";m["calibration"]=motion.calibration;
 m["possibleFall"]=valid&&(motion.state==FallState::POSSIBLE_FALL||motion.state==FallState::VERIFYING||motion.state==FallState::FALL_ALERT);
 m["fallState"]=valid?fallName(motion.state):"NORMAL";m["eventId"]=motion.eventId;m["impactCount"]=motion.impactCount;m["impact"]=valid&&motion.impact;m["inactive"]=valid&&motion.inactive;
 m["activity"]=!valid?"UNAVAILABLE":motion.moving?"MOVING":"REST";m["accelerationUnit"]="g";
 auto t=d["temperature"].to<JsonObject>(); bool freshTemperature=imuOK&&chipTemperature.valid(millis());
 t["celsius"]=nullptr;if(freshTemperature)t["celsius"]=chipTemperature.celsius;
 t["quality"]=imuOK?chipTemperature.quality(millis()):"UNAVAILABLE";
 t["source"]="LSM6DS3_INTERNAL";t["unit"]="C";t["sampleAgeMs"]=nullptr;
 if(chipTemperature.present)t["sampleAgeMs"]=uint32_t(millis()-chipTemperature.sampledAt);
 d["sensorStatus"]["max30102"]=maxOK?"OK":"UNAVAILABLE";d["sensorStatus"]["lsm6ds3"]=imuOK?"OK":"UNAVAILABLE";
 Packet p={};p.created=millis();if(measureJson(d)>=sizeof(p.json)){Serial.println("Telemetry exceeds packet capacity");return;}serializeJson(d,p.json,sizeof(p.json));
 xQueueOverwrite(serialQueue,&p);xQueueOverwrite(displayQueue,&p);xQueueOverwrite(networkQueue,&p);
}
void serialWorker(void*){
 Packet p;while(true){if(xQueueReceive(serialQueue,&p,portMAX_DELAY)!=pdTRUE)continue;
  Serial.print("JSON: ");Serial.println(p.json);JsonDocument d;if(deserializeJson(d,p.json))continue;
  Serial.printf("LSM6DS3 %s | %s | temperature %s | WiFi %s server %s\n",
   d["motion"]["quality"]|"UNAVAILABLE",d["motion"]["calibration"]|"UNVERIFIED",
   d["temperature"]["quality"]|"UNAVAILABLE",wifiConnected?"OK":"OFFLINE",backendOK?"OK":"OFFLINE");
 }
}
#include "NetworkRuntime.h"

// ================= RECOVERY =================

void recoverSensors() {
  if (imuOK && !i2cPresent(IMU_ADDR))
    imuOK = false;

  if (maxOK && !i2cPresent(MAX_ADDR)) {
    maxOK = false;
    fingerDetected = false;
    validHR = 0;
    validSpO2 = 0;
    sampleIndex = 0;
  }

  // OLED recovery belongs to oledWorker, which alone owns its framebuffer.
  if (!imuOK) initIMU();
  if (!maxOK) initMAX();
}

// ================= SETUP =================

void setup() {
  Serial.begin(115200);
  delay(1200);

  Serial.println();
  Serial.println("================================");
  Serial.println("     SMARTTRACK WATCH STARTING");
  Serial.println("================================");

  setenv("TZ","UTC0",1);tzset();
  busMutex=xSemaphoreCreateMutex();networkQueue=xQueueCreate(1,sizeof(Packet));displayQueue=xQueueCreate(1,sizeof(Packet));commandQueue=xQueueCreate(1,sizeof(Commands));serialQueue=xQueueCreate(1,sizeof(Packet));
  watchMotionQueue=xQueueCreate(1,sizeof(smartwatch::WatchMotionSample));
  if(!busMutex||!networkQueue||!displayQueue||!commandQueue||!serialQueue||!watchMotionQueue){Serial.println("Allocation failed");while(true)delay(1000);}
  pinMode(WATCH_TOUCH1_PIN,WATCH_TOUCH_ACTIVE_HIGH?INPUT_PULLDOWN:INPUT_PULLUP);
  pinMode(WATCH_TOUCH2_PIN,WATCH_TOUCH_ACTIVE_HIGH?INPUT_PULLDOWN:INPUT_PULLUP);
  deviceId=DEVICE_ID;if(deviceId.length()==0){char id[32];snprintf(id,sizeof(id),"esp32-%012llx",(unsigned long long)ESP.getEfuseMac());deviceId=id;}
  char boot[40];snprintf(boot,sizeof(boot),"%08lx-%08lx-%08lx",(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random());bootId=boot;
  motion.config.sensitivity=MOTION_SENSITIVITY;motion.config.lowG=LOW_G_THRESHOLD;motion.config.impactG=IMPACT_THRESHOLD_G;motion.config.inactivityMs=FALL_INACTIVITY_MS;motion.config.cooldownMs=FALL_COOLDOWN_MS;
  motion.config.impactWindowMs=IMPACT_WINDOW_MS;motion.config.verificationMs=VERIFICATION_WINDOW_MS;motion.config.alertHoldMs=FALL_ALERT_HOLD_MS;
  motion.config.rotationDps=ROTATION_TRIGGER_DPS;motion.config.rotationDegrees=REQUIRED_ROTATION_DEGREES;motion.config.orientationDegrees=REQUIRED_ORIENTATION_DEGREES;
  Wire.begin(I2C_SDA, I2C_SCL);Wire.setTimeOut(10);
  Wire.setClock(100000);

  scanI2C();

  initOLED();
  initIMU();
  initMAX();

  Serial.print("Device ID: ");Serial.println(deviceId);
  Serial.println("Serial commands: C cancel fall; K calibrate stationary flat +Z up");
  xTaskCreatePinnedToCore(oledWorker,"oled-worker",6144,nullptr,1,nullptr,0);
  xTaskCreatePinnedToCore(serialWorker,"serial-worker",6144,nullptr,1,nullptr,0);
  xTaskCreatePinnedToCore(networkWorker,"wifi-worker",10240,nullptr,1,nullptr,0);
  Serial.println("WATCH: T1 open / hold home; T2 next / hold previous; both display power");
  Serial.println("SYSTEM READY");
}

// ================= LOOP =================

void loop() {
  if(uint32_t(millis()-lastTemperature)>=1000){lastTemperature=millis();updateTemperature();}
  applyCommands();
  updateHeartSensor();

  unsigned long now = millis();

  if (now - lastMotion >= MOTION_INTERVAL) {
    lastMotion = now;
    updateMotion();
  }

  if (now - lastSerial >= SERIAL_INTERVAL) {
    lastSerial = now;
    // UART transmission runs on serialWorker; long diagnostics must not stall 50 Hz acquisition.
    printJSON();
  }

  if (now - lastRecovery >= RECOVERY_INTERVAL) {
    lastRecovery = now;
    if(lockBus()){recoverSensors();unlockBus();}
  }

  delay(1);
}
