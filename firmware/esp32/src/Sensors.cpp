#include "DeviceState.h"
#include <Wire.h>
#include <MAX30105.h>
#include <spo2_algorithm.h>
#include <SparkFunLSM6DS3.h>
#include <TinyGPSPlus.h>
DeviceState state;
static MAX30105 pulse;
static LSM6DS3 imu(I2C_MODE, 0x6B);
static TinyGPSPlus gps;
static HardwareSerial gpsUart(2);
static constexpr uint16_t SPO2_SAMPLE_COUNT = 100;
static uint32_t ir[SPO2_SAMPLE_COUNT], red[SPO2_SAMPLE_COUNT];
static uint32_t sampleTimes[SPO2_SAMPLE_COUNT];
static bool captureRequested = false;
static bool pulseEnabled = true, pulsePresent = false, idleSleeping = true;
static uint32_t idleSince = 0, probeStarted = 0, probeInitialSamples = 0;
static uint16_t samples = 0;
static constexpr uint8_t PULSE_CURRENT = 60;
static uint8_t redCurrent = 0, irCurrent = PULSE_CURRENT;
static uint32_t lastCurrentChange = 0;
static uint8_t contactSamples = 0, absentSamples = 0, settlingSamples = 0;
static uint32_t rawIrLatest = 0, rawRedLatest = 0, pulseSamples = 0, pulseLost = 0,
                pulseWindows = 0, windowSpan = 0, irRange = 0, redRange = 0;
static int32_t candidateSpo2 = 0, candidateBpm = 0;
static int8_t candidateSpo2Valid = 0, candidateBpmValid = 0;
static const char* pulseReason = "STARTING";
static uint32_t imuTick = 0, healthTick = 0, retryTick = 0;
static float biasX = 0, biasY = 0, biasZ = 0, sumX = 0, sumY = 0, sumZ = 0;
static uint16_t calibrationSamples = 0;
static void clearHealth(const char* reason) {
  samples = 0;
  state.healthValid = false;
  state.bpm = state.spo2 = NAN;
  pulseReason = reason;
}
static void discardPulseFifo() {
  while (pulse.available()) pulse.nextSample();
  pulse.clearFIFO();
  // The library's FIFO tail is one sample behind its head. Discard that old value
  // and the first new value after changing LED current or resetting the FIFO.
  settlingSamples = 2;
}
static void sleepPulse(uint32_t now) {
  pulse.setPulseAmplitudeRed(0);
  pulse.setPulseAmplitudeIR(0);
  pulse.shutDown();
  redCurrent = irCurrent = 0;
  state.finger = false;
  contactSamples = absentSamples = 0;
  clearHealth("NO_CONTACT");
  idleSleeping = true;
  idleSince = now;
  discardPulseFifo();
}
static bool beginPulse() {
  pulsePresent = pulse.begin(Wire, I2C_SPEED_FAST);
  if (!pulsePresent) return false;
  // The Maxim algorithm expects 25 paired FIFO records/sec: 100 Hz / 4 averaging.
  // This board clipped its red ADC at 4096 nA. Use the next range and tune LED
  // currents below, keeping both optical channels away from the ADC rails.
  pulse.setup(PULSE_CURRENT, 4, 2, 100, 411, 8192);
  pulse.setPulseAmplitudeGreen(0);
  lastCurrentChange = millis();
  // Sleep between short IR-only contact probes; neither emitter runs continuously
  // with the optical window uncovered. Measurement stays at uninterrupted 25 Hz.
  sleepPulse(millis());
  state.lastHealthMs = millis();
  return true;
}
void pulseControl(bool enabled) {
  pulseEnabled = enabled;
  if (enabled) {
    state.maxAvailable = beginPulse();
    Serial.println(state.maxAvailable ? "Pulse sensor enabled" : "Pulse sensor unavailable");
  } else {
    if (pulsePresent) sleepPulse(millis());
    state.finger = false;
    state.maxAvailable = false;
    captureRequested = false;
    clearHealth("DISABLED");
    Serial.println("Pulse sensor disabled; both emitter currents zero");
  }
}
void pulseDiagnostics() {
  Serial.printf("pulse enabled=%d mode=%u finger=%d redLed=%u irLed=%u rawIR=%u rawRed=%u samples=%u "
                "lost=%u windows=%u buffered=%u windowMs=%u irRange=%u redRange=%u "
                "hrCandidate=%d hrValid=%d spo2Candidate=%d spo2Valid=%d reason=%s\n",
                pulseEnabled, pulsePresent ? pulse.readRegister8(0x57, 0x09) : 0,
                state.finger, pulsePresent ? pulse.readRegister8(0x57, 0x0C) : 0,
                pulsePresent ? pulse.readRegister8(0x57, 0x0D) : 0,
                rawIrLatest, rawRedLatest, pulseSamples, pulseLost, pulseWindows, samples,
                windowSpan, irRange, redRange, candidateBpm, candidateBpmValid,
                candidateSpo2, candidateSpo2Valid, pulseReason);
}
void pulseCapture() {
  captureRequested = true;
  Serial.println("PPG capture armed: waiting for one complete contact window");
}
void sensorsBegin() {
  Wire.begin(21, 22);
  Wire.setClock(400000);
  Wire.setTimeOut(20);
  gpsUart.begin(9600, SERIAL_8N1, 26, 27);
  state.maxAvailable = beginPulse();
  imu.settings.accelRange = 16;
  imu.settings.gyroRange = 2000;
  imu.settings.accelSampleRate = 104;
  imu.settings.gyroSampleRate = 104;
  state.imuAvailable = imu.begin() == 0;
}
void calibrateMotion() {
  state.calibrating = true;
  calibrationSamples = 0;
  sumX = sumY = sumZ = 0;
}
static void health(uint32_t now) {
  if (idleSleeping) {
    // Deliberate sleep is not a failed or stale sensor.
    state.lastHealthMs = now;
    if (now - idleSince < 800) return;
    pulse.setPulseAmplitudeIR(PULSE_CURRENT);
    irCurrent = PULSE_CURRENT;
    pulse.wakeUp();
    idleSleeping = false;
    probeStarted = now;
    probeInitialSamples = pulseSamples;
    discardPulseFifo();
    return;
  }
  if (!state.finger && now - probeStarted >= 320) {
    bool responsive = pulseSamples - probeInitialSamples >= 3;
    sleepPulse(now);
    if (!responsive) {
      state.maxAvailable = false;
      clearHealth("SENSOR_TIMEOUT");
    }
    return;
  }
  uint16_t arrived = pulse.check();
  if (arrived >= STORAGE_SIZE) {
    // The library's four-slot software ring cannot retain this batch. Never
    // calculate from a window with silently missing or overwritten samples.
    pulseLost += arrived;
    clearHealth("FIFO_GAP");
    discardPulseFifo();
    state.lastHealthMs = now;
    return;
  }
  while (pulse.available()) {
    // Read both channels from the same queued sample. getIR()/getRed() each
    // wait for a NEW sample and must not be used to drain this FIFO.
    uint32_t rawIr = pulse.getFIFOIR(), rawRed = pulse.getFIFORed();
    pulse.nextSample();
    state.lastHealthMs = now;
    rawIrLatest = rawIr;
    rawRedLatest = rawRed;
    pulseSamples++;
    if (settlingSamples) {
      settlingSamples--;
      continue;
    }
    if (!state.finger) {
      contactSamples = rawIr > 50000 ? contactSamples + 1 : 0;
      if (contactSamples >= 3) {
        state.finger = true;
        contactSamples = absentSamples = 0;
        pulse.setPulseAmplitudeRed(PULSE_CURRENT);
        redCurrent = PULSE_CURRENT;
        lastCurrentChange = now;
        clearHealth("ACQUIRING");
        discardPulseFifo();
        break;
      }
      continue;
    }
    if (rawIr < 35000) {
      clearHealth("CONTACT_LOST");
      if (++absentSamples >= 5) {
        sleepPulse(now);
        break;
      }
      continue;
    }
    absentSamples = 0;
    if (now - lastCurrentChange >= 500) {
      uint8_t nextRed = redCurrent, nextIr = irCurrent;
      if (rawRed > 180000 && redCurrent > 4) nextRed = max(4, redCurrent * 3 / 4);
      else if (rawRed < 30000 && redCurrent < PULSE_CURRENT)
        nextRed = min(int(PULSE_CURRENT), redCurrent + 4);
      if (rawIr > 180000 && irCurrent > 4) nextIr = max(4, irCurrent * 3 / 4);
      else if (rawIr < 60000 && irCurrent < PULSE_CURRENT)
        nextIr = min(int(PULSE_CURRENT), irCurrent + 4);
      if (nextRed != redCurrent || nextIr != irCurrent) {
        redCurrent = nextRed;
        irCurrent = nextIr;
        pulse.setPulseAmplitudeRed(redCurrent);
        pulse.setPulseAmplitudeIR(irCurrent);
        lastCurrentChange = now;
        clearHealth("LED_SETTLING");
        discardPulseFifo();
        break;
      }
    }
    sampleTimes[samples] = now;
    ir[samples] = rawIr;
    red[samples] = rawRed;
    samples++;
    if (samples == SPO2_SAMPLE_COUNT) {
      int32_t saturation = 0, rate = 0;
      int8_t validSpo2 = 0, validRate = 0;
      maxim_heart_rate_and_oxygen_saturation(ir, SPO2_SAMPLE_COUNT, red, &saturation, &validSpo2,
                                             &rate, &validRate);
      uint32_t minIr = ir[0], maxIr = ir[0], minRed = red[0], maxRed = red[0];
      for (uint16_t i = 1; i < SPO2_SAMPLE_COUNT; i++) {
        minIr = min(minIr, ir[i]);
        maxIr = max(maxIr, ir[i]);
        minRed = min(minRed, red[i]);
        maxRed = max(maxRed, red[i]);
      }
      // Timestamp the oldest retained sample; adding a nominal 1000 ms on each
      // slide accumulates oscillator drift and eventually rejects good windows.
      windowSpan = now - sampleTimes[0];
      irRange = maxIr - minIr;
      redRange = maxRed - minRed;
      candidateBpm = rate;
      candidateSpo2 = saturation;
      candidateBpmValid = validRate;
      candidateSpo2Valid = validSpo2;
      pulseWindows++;
      bool cadence = windowSpan >= 3500 && windowSpan <= 4500;
      bool signal = irRange > 300 && redRange > 100 && minRed > 500 &&
                    maxIr < 260000 && maxRed < 260000;
      state.healthValid = cadence && signal && validSpo2 && validRate && rate >= 25 &&
                          rate <= 240 && saturation >= 50 && saturation <= 100;
      state.bpm = state.healthValid ? rate : NAN;
      state.spo2 = state.healthValid ? saturation : NAN;
      pulseReason = !cadence ? "SAMPLE_TIMING" : !signal ? "LOW_OR_CLIPPED_SIGNAL"
                    : state.healthValid ? "VALID" : "ALGORITHM_REJECTED";
      if (captureRequested) {
        JsonDocument capture;
        capture["windowMs"] = windowSpan;
        capture["redCurrent"] = redCurrent;
        capture["irCurrent"] = irCurrent;
        auto capturedIr = capture["ir"].to<JsonArray>();
        auto capturedRed = capture["red"].to<JsonArray>();
        for (uint16_t i = 0; i < SPO2_SAMPLE_COUNT; i++) {
          capturedIr.add(ir[i]);
          capturedRed.add(red[i]);
        }
        if (queuePulseCapture(capture)) captureRequested = false;
      }
      // 1 second sliding update; 4 second acquisition window at the effective 25 Hz.
      for (uint16_t i = 0; i < 75; i++) {
        ir[i] = ir[i + 25];
        red[i] = red[i + 25];
        sampleTimes[i] = sampleTimes[i + 25];
      }
      samples = 75;
    }
  }
  if (now - state.lastHealthMs > 1000) {
    clearHealth("SENSOR_TIMEOUT");
    state.finger = false;
    pulse.setPulseAmplitudeRed(0);
    pulse.setPulseAmplitudeIR(0);
    state.maxAvailable = false;
  }
}
static float filtered(float old, float value) {
  return std::isfinite(old) ? old * 0.75f + value * 0.25f : value;
}
static void motion(uint32_t now) {
  float x = imu.readFloatAccelX(), y = imu.readFloatAccelY(), z = imu.readFloatAccelZ(),
        gx = imu.readFloatGyroX(), gy = imu.readFloatGyroY(), gz = imu.readFloatGyroZ();
  float magnitude = sqrtf(x * x + y * y + z * z);
  if (!std::isfinite(magnitude) || magnitude > 28) {
    state.imuAvailable = false;
    return;
  }
  if (state.calibrating) {
    if (fabsf(magnitude - 1) > 0.08f || fabsf(gx) > 5 || fabsf(gy) > 5 || fabsf(gz) > 5) {
      state.calibrating = false;
      Serial.println("Calibration cancelled: keep device stationary");
    } else {
      sumX += gx;
      sumY += gy;
      sumZ += gz;
      if (++calibrationSamples == 256) {
        biasX = sumX / 256;
        biasY = sumY / 256;
        biasZ = sumZ / 256;
        state.calibrating = false;
        Serial.println("Gyroscope bias calibration complete (RAM)");
      }
    }
  }
  state.ax = filtered(state.ax, x);
  state.ay = filtered(state.ay, y);
  state.az = filtered(state.az, z);
  state.gx = filtered(state.gx, gx - biasX);
  state.gy = filtered(state.gy, gy - biasY);
  state.gz = filtered(state.gz, gz - biasZ);
  state.temperature = imu.readTempC();
  state.magnitude = magnitude;
  state.pitch = atan2f(-state.ax, sqrtf(state.ay * state.ay + state.az * state.az)) * 180 / PI;
  state.roll = atan2f(state.ay, state.az) * 180 / PI;
  state.activity =
      fabsf(magnitude - 1) < 0.08f && fabsf(state.gx) + fabsf(state.gy) + fabsf(state.gz) < 8
          ? "STILL"
      : magnitude > 1.8f ? "VIGOROUS"
                         : "MOVING";
  if (state.fall.update(now, magnitude, state.pitch, state.roll)) state.fallEvent = true;
  if (magnitude > 3.5f && now - state.lastImpact > 3000) {
    state.impactEvent = true;
    state.lastImpact = now;
  }
}
void sensorsTick(uint32_t now) {
  if (pulseEnabled && state.maxAvailable && now - healthTick >= 10) {
    healthTick = now;
    health(now);
  }
  if (state.imuAvailable && now - imuTick >= 10) {
    imuTick = now;
    motion(now);
  }
  uint16_t budget = 256;
  while (gpsUart.available() && budget--) {
    gps.encode(gpsUart.read());
    state.lastGpsChar = now;
  }
  state.gpsCharacters = gps.charsProcessed();
  state.gpsChecksumFailures = gps.failedChecksum();
  state.gpsCommunicating = state.gpsCharacters > 0 && now - state.lastGpsChar < 3000;
  state.satellites = gps.satellites.isValid() ? gps.satellites.value() : 0;
  state.gpsFix = state.gpsCommunicating && gps.location.isValid() && gps.location.age() < 5000;
  if (state.gpsFix) {
    bool freshFix = gps.location.isUpdated();
    state.latitude = gps.location.lat();
    state.longitude = gps.location.lng();
    state.altitude = gps.altitude.isValid() ? gps.altitude.meters() : NAN;
    state.speed = gps.speed.isValid() ? gps.speed.kmph() : NAN;
    if (freshFix) {
      state.lastFixMs = now;
      state.lastFixAt = utcTimestamp();
    }
  }
  if (gps.date.isValid() && gps.time.isValid() && gps.date.year() >= 2024 &&
      gps.time.age() < 1500 && String(clockQuality()) != "NTP") {
    static uint32_t lastGpsClock = 0;
    if (now - lastGpsClock > 60000 || String(clockQuality()) == "UNSYNCHRONIZED") {
      struct tm tm = {};
      tm.tm_year = gps.date.year() - 1900;
      tm.tm_mon = gps.date.month() - 1;
      tm.tm_mday = gps.date.day();
      tm.tm_hour = gps.time.hour();
      tm.tm_min = gps.time.minute();
      tm.tm_sec = gps.time.second();
      extern void synchronizeGps(const struct tm&);
      synchronizeGps(tm);
      lastGpsClock = now;
    }
  }
  if (now - retryTick > 5000) {
    retryTick = now;
    if (pulseEnabled && !state.maxAvailable) {
      state.maxAvailable = beginPulse();
      samples = 0;
      state.lastHealthMs = now;
    }
    if (!state.imuAvailable) state.imuAvailable = imu.begin() == 0;
  }
}
