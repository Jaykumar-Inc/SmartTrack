#pragma once
#include <stdint.h>
#include <math.h>
#include "TouchControls.h"

namespace smartwatch {
enum class Screen : uint8_t { Home, HeartRate, Oxygen, Motion, Temperature, Alerts, Settings, Count };
enum class Setting : uint8_t { Brightness, Timeout, Raise, Count };

struct WatchMotionSample {
  float ax, ay, az, gx, gy, gz;
  uint32_t created;
};

// Geometric gesture using real copied IMU samples; never reads I2C in the UI task.
class RaiseToWake {
 public:
  RaiseToWake(float faceSign = 1, float lowerZ = .30f, float upZ = .75f)
      : sign_(faceSign), lowerZ_(lowerZ), upZ_(upZ) {}
  bool update(const WatchMotionSample& s) {
    if (seen_ && uint32_t(s.created - last_) > 250) reset();
    last_ = s.created; seen_ = true;
    float magnitude = sqrtf(s.ax*s.ax + s.ay*s.ay + s.az*s.az);
    float speed = sqrtf(s.gx*s.gx + s.gy*s.gy + s.gz*s.gz);
    if (!isfinite(magnitude) || !isfinite(speed) || magnitude < .65f || magnitude > 1.35f) {
      reset(); return false;
    }
    float face = sign_ * s.az / magnitude;
    if (face < lowerZ_) {
      if (!lowered_) { lowered_ = true; lowerAt_ = s.created; }
      if (uint32_t(s.created-lowerAt_) >= 250) { armed_ = true; lastLower_ = s.created; }
      facing_ = false;
    } else lowered_ = false;
    if (!armed_) return false;
    if (uint32_t(s.created-lastLower_) > 2000) { reset(); return false; }
    if (speed >= 20) { rotated_ = true; rotatedAt_ = s.created; }
    if (face >= upZ_ && rotated_ && uint32_t(s.created-rotatedAt_) <= 1500) {
      if (!facing_) { facing_ = true; faceAt_ = s.created; }
      if (uint32_t(s.created-faceAt_) >= 160) { reset(); return true; }
    } else facing_ = false;
    return false;
  }
  void reset() { lowered_ = armed_ = rotated_ = facing_ = false; }
 private:
  float sign_, lowerZ_, upZ_;
  bool seen_ = false, lowered_ = false, armed_ = false, rotated_ = false, facing_ = false;
  uint32_t last_ = 0, lowerAt_ = 0, lastLower_ = 0, rotatedAt_ = 0, faceAt_ = 0;
};

class WatchState {
 public:
  Screen screen = Screen::Home;
  Setting setting = Setting::Brightness;
  bool awake = true, opened = true, details = false, editing = false, raiseEnabled;
  uint8_t brightness;
  uint32_t timeoutMs, revision = 1;
  uint8_t alertIndex = 0;

  WatchState(uint32_t timeout = 15000, uint8_t contrast = 128, bool raise = true)
      : raiseEnabled(raise), brightness(contrast), timeoutMs(timeout) {}
  void begin(uint32_t now) { lastActivity_ = now; transitionAt_ = now; }
  void touch(uint8_t events, bool held, uint32_t now) {
    if (events & TouchBegin) {
      if (!gesture_) { gesture_ = true; initiallyAwake_ = awake; consumed_ = !awake; }
      if (!awake && !chord_) wake(now);
    }
    if (events & TouchBoth) {
      chord_ = consumed_ = true;
      if (initiallyAwake_) sleep(now); else wake(now);
    }
    if (held && awake) lastActivity_ = now;
    if (!consumed_ && awake) {
      if (events & Touch1Long) home(now);
      else if (events & Touch1Short) select(now);
      else if (events & Touch2Short) next(1, now);
      else if (events & Touch2Long) next(-1, now);
    }
    if (events & TouchIdle) { gesture_ = consumed_ = chord_ = false; }
  }
  void tick(uint32_t now) {
    if (awake && timeoutMs && uint32_t(now-lastActivity_) >= timeoutMs) sleep(now);
  }
  void raise(uint32_t now) {
    // Manual off is respected until a new lower/lift gesture after this guard.
    if (!awake && raiseEnabled && uint32_t(now-sleptAt_) >= 2000) { home(now); wake(now); }
  }
  int16_t slideOffset(uint32_t now, uint32_t duration) const {
    uint32_t elapsed = now - transitionAt_;
    if (!duration || elapsed >= duration) return 0;
    uint32_t remaining = duration-elapsed;
    return int16_t(direction_ * int32_t(24UL*remaining*remaining/duration/duration));
  }
  bool animating(uint32_t now, uint32_t duration) const {
    return uint32_t(now-transitionAt_) < duration;
  }
 private:
  uint32_t lastActivity_ = 0, transitionAt_ = 0, sleptAt_ = 0;
  int8_t direction_ = 1;
  bool gesture_ = false, initiallyAwake_ = true, consumed_ = false, chord_ = false;
  void changed(uint32_t now, int8_t direction = 1) {
    lastActivity_ = transitionAt_ = now; direction_ = direction; ++revision;
  }
  void home(uint32_t now) {
    screen = Screen::Home; opened = true; editing = details = false; changed(now, -1);
  }
  void wake(uint32_t now) { awake = true; lastActivity_ = now; ++revision; }
  void sleep(uint32_t now) { awake = false; sleptAt_ = now; editing = false; ++revision; }
  void next(int8_t direction, uint32_t now) {
    if (editing) { adjust(direction); lastActivity_ = now; ++revision; return; }
    int page = int(screen) + direction;
    const int count = int(Screen::Count);
    screen = Screen((page+count)%count); opened = screen == Screen::Home;
    details = false; changed(now, direction);
  }
  void select(uint32_t now) {
    if (!opened) { opened = true; changed(now); return; }
    if (screen == Screen::Home) { screen = Screen::HeartRate; opened = false; changed(now); return; }
    if (screen == Screen::Settings) {
      if (editing) { editing = false; setting = Setting((int(setting)+1)%int(Setting::Count)); }
      else editing = true;
    } else if (screen == Screen::Alerts) ++alertIndex;
    else details = !details;
    lastActivity_ = now; ++revision;
  }
  void adjust(int8_t direction) {
    if (setting == Setting::Brightness) {
      const uint8_t values[] = {16, 64, 128, 192, 255};
      int current = 0;
      for (int i=0;i<5;++i) if (brightness >= values[i]) current=i;
      brightness=values[(current+direction+5)%5];
    } else if (setting == Setting::Timeout) {
      const uint32_t values[] = {5000, 10000, 15000, 30000, 60000, 0};
      int current=2;
      for(int i=0;i<6;++i) if(timeoutMs==values[i]) current=i;
      timeoutMs=values[(current+direction+6)%6];
    } else raiseEnabled = !raiseEnabled;
  }
};
} // namespace smartwatch
