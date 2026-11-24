#pragma once
#include "WatchRenderer.h"

// Included by SmartTrack.ino after its sensor helpers. This worker exclusively
// owns the OLED object after setup. MAX30102/IMU acquisition never calls the UI.
namespace {
uint8_t watchOledShadow[SCREEN_WIDTH * SCREEN_HEIGHT / 8];
bool watchShadowValid = false;
uint8_t watchOledFailures = 0;

bool watchOledCommand(const uint8_t* commands, size_t length) {
  if (!lockBus()) return false;
  Wire.beginTransmission(OLED_ADDR); Wire.write(0x00); Wire.write(commands,length);
  bool ok = Wire.endTransmission() == 0;
  unlockBus();
  if (ok) watchOledFailures=0;
  else if (++watchOledFailures>=5) oledOK=false;
  return ok;
}

bool flushWatchOLED(OledFrameTransfer& frame) {
  // Existing acknowledged cursor/retry behaviour, plus dirty-chunk suppression.
  // At most two short transactions per pass; no full-frame lock or second canvas.
  uint8_t sent=0;
  while (!frame.complete() && sent<2) {
    auto chunk=frame.next();
    const uint8_t* pixels=oled.getBuffer()+chunk.offset;
    if(watchShadowValid && memcmp(pixels,watchOledShadow+chunk.offset,chunk.length)==0) {
      frame.acknowledge(true);continue;
    }
    if(!lockBus()) return false;
    const uint8_t commands[]={0x20,0x00,0x21,chunk.column,uint8_t(chunk.column+chunk.length-1),0x22,chunk.page,chunk.page};
    Wire.beginTransmission(OLED_ADDR);Wire.write(0x00);Wire.write(commands,sizeof(commands));
    bool success=Wire.endTransmission()==0;
    if(success) {
      Wire.beginTransmission(OLED_ADDR);Wire.write(0x40);Wire.write(pixels,chunk.length);
      success=Wire.endTransmission()==0;
    }
    unlockBus();
    if(success) {
      memcpy(watchOledShadow+chunk.offset,pixels,chunk.length);watchOledFailures=0;
    } else if(++watchOledFailures>=5) oledOK=false;
    frame.acknowledge(success);++sent;
    if(!success) return false;
    vTaskDelay(pdMS_TO_TICKS(1));
  }
  if(frame.complete()) {watchShadowValid=true;return true;}
  return false;
}
} // namespace

void oledWorker(void*) {
  using namespace smartwatch;
  TouchControls touch(WATCH_DEBOUNCE_MS,WATCH_LONG_PRESS_MS);
  WatchState watch(WATCH_SCREEN_TIMEOUT_MS,WATCH_BRIGHTNESS,WATCH_RAISE_TO_WAKE);
  RaiseToWake raise(WATCH_RAISE_FACE_SIGN,WATCH_RAISE_LOWER_Z,WATCH_RAISE_UP_Z);
  WatchRenderer renderer(oled);
  Packet packet;JsonDocument data;
  OledFrameTransfer frame(SCREEN_WIDTH,SCREEN_HEIGHT);
  WatchMotionSample sample;
  uint32_t snapshotAt=0,lastProbe=0,lastFrame=0,lastTouch=0,renderedRevision=0,renderedSecond=UINT32_MAX;
  int16_t renderedOffset=INT16_MIN;
  bool transmitting=false,dataChanged=true,lastWifi=false,lastServer=false;
  int8_t powered=-1;int16_t contrast=-1;
  watch.begin(millis());
  for(;;) {
    uint32_t now=millis();
    if(uint32_t(now-lastTouch)>=5) {
      lastTouch=now;
      bool first=(digitalRead(WATCH_TOUCH1_PIN)==HIGH)==bool(WATCH_TOUCH_ACTIVE_HIGH);
      bool second=(digitalRead(WATCH_TOUCH2_PIN)==HIGH)==bool(WATCH_TOUCH_ACTIVE_HIGH);
      uint8_t events=touch.update(first,second,now);
      watch.touch(events,touch.held(),now);
    }
    if(xQueueReceive(watchMotionQueue,&sample,0)==pdTRUE) {
      if(uint32_t(now-sample.created)<=250 && raise.update(sample)) watch.raise(now);
    }
    watch.tick(now);
    if(xQueueReceive(displayQueue,&packet,0)==pdTRUE) {
      if(!deserializeJson(data,packet.json)) {snapshotAt=packet.created;dataChanged=true;}
    }
    if(uint32_t(now-lastProbe)>=RECOVERY_INTERVAL) {
      lastProbe=now;
      if(lockBus()) {
        if(oledOK && !i2cPresent(OLED_ADDR)) oledOK=false;
        if(!oledOK) {
          transmitting=false;watchShadowValid=false;powered=-1;contrast=-1;
          initOLED();
          if(oledOK) {watchOledFailures=0;dataChanged=true;}
        }
        unlockBus();
      }
    }
    if(!oledOK) {
      transmitting=false;watchShadowValid=false;powered=-1;contrast=-1;
      vTaskDelay(pdMS_TO_TICKS(5));continue;
    }
    if(powered!=int(watch.awake)) {
      const uint8_t command=watch.awake?SSD1306_DISPLAYON:SSD1306_DISPLAYOFF;
      if(watchOledCommand(&command,1)) {powered=watch.awake;dataChanged=true;}
    }
    if(!watch.awake) {
      transmitting=false;vTaskDelay(pdMS_TO_TICKS(5));continue;
    }
    if(contrast!=watch.brightness) {
      const uint8_t commands[]={SSD1306_SETCONTRAST,watch.brightness};
      if(watchOledCommand(commands,sizeof(commands))) contrast=watch.brightness;
    }
    bool linked=wifiConnected;
    bool serverReady=linked && backendOK;
    int16_t offset=watch.slideOffset(now,WATCH_TRANSITION_MS);
    bool dirty=dataChanged || watch.revision!=renderedRevision || now/1000!=renderedSecond ||
               offset!=renderedOffset || linked!=lastWifi || serverReady!=lastServer;
    if(!transmitting && dirty && uint32_t(now-lastFrame)>=WATCH_FRAME_INTERVAL_MS) {
      renderer.draw(watch,data,snapshotAt,now,{true,linked,serverReady},WATCH_UTC_OFFSET_SECONDS,WATCH_TRANSITION_MS);
      // From here until completion the framebuffer is frozen, including new touches.
      frame.start();transmitting=true;lastFrame=now;renderedSecond=now/1000;
      renderedRevision=watch.revision;lastWifi=linked;lastServer=serverReady;dataChanged=false;
      renderedOffset=offset; // Also forces the final zero-offset frame after a slow transfer.
    }
    if(transmitting && flushWatchOLED(frame)) transmitting=false;
    vTaskDelay(pdMS_TO_TICKS(1));
  }
}
