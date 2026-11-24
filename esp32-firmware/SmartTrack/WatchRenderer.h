#pragma once
#include <Adafruit_SSD1306.h>
#include <ArduinoJson.h>
#include <time.h>
#include "WatchState.h"
#include "WiFiConfig.h"

namespace smartwatch {
struct WatchConnection { bool enabled, connected, server;
  WatchConnection(bool e,bool c,bool s):enabled(e),connected(c),server(s){}
};

// Uses only the existing Adafruit framebuffer. No bitmaps, Strings, or extra canvas.
class WatchRenderer {
 public:
  explicit WatchRenderer(Adafruit_SSD1306& display) : oled_(display) {}
  void draw(const WatchState& state, const JsonDocument& data, uint32_t captured,
            uint32_t now, WatchConnection link, int32_t utcOffset, uint32_t transitionMs) {
    oled_.clearDisplay(); oled_.setTextWrap(false); oled_.setTextSize(1);
    oled_.setTextColor(SSD1306_WHITE);
    offset_ = state.slideOffset(now, transitionMs);
    bool fresh = !data.isNull() && uint32_t(now-captured) <= 3000;
    const char* names[] = {"SMARTTRACK", "HEART RATE", "OXYGEN", "MOTION", "CHIP TEMP", "ALERTS", "SETTINGS"};
    oled_.setCursor(0,0); oled_.print(names[int(state.screen)]);
    badge(104, link.connected, 'W'); badge(117, link.server && link.connected, 'S');
    oled_.drawFastHLine(0,10,128,SSD1306_WHITE);
    if (oled_.height() == 32) { compact(state,data,fresh,link,now,utcOffset); return; }
    if (!state.opened) {
      icon(state.screen, 53+offset_, 15);
      centered(names[int(state.screen)], 39);
      footer("T1 OPEN  T2 NEXT", state.screen);
      return;
    }
    if (state.screen == Screen::Home) home(now,utcOffset,fresh,state.screen);
    else if (state.screen == Screen::Settings) settings(state,link);
    else if (state.screen == Screen::Alerts) alerts(state,data,fresh,link);
    else if (!fresh) {
      centered(data.isNull()?"WAITING FOR DATA":"SENSOR DATA STALE",24);
      centered("No current reading",38); footer("MONITOR CONTINUES",state.screen);
    } else {
      JsonObjectConst h=data["health"], m=data["motion"], t=data["temperature"];
      if (state.screen==Screen::HeartRate || state.screen==Screen::Oxygen) {
        const bool oxygen=state.screen==Screen::Oxygen;
        JsonVariantConst value=oxygen?h["spo2"]:h["bpm"];
        if (!state.details) {
          char number[12]; if(value.isNull()) snprintf(number,sizeof(number),"--");
          else snprintf(number,sizeof(number),"%.0f",value.as<double>());
          text(9,17,number,3); text(82,31,oxygen?"% EST":"BPM");
          text(9,45,h["quality"]|"UNAVAILABLE");
        } else {
          text(0,16,h["fingerPresent"].as<bool>()?"Finger detected":"No finger contact");
          text(0,28,h["quality"]|"UNAVAILABLE");
          text(0,40,oxygen?"Not a clinical value":"Stable contact needed");
        }
        footer("T1 DETAILS",state.screen);
      } else if(state.screen==Screen::Motion) {
        if(m["ax"].isNull()) {
          text(0,17,m["quality"]|"UNAVAILABLE"); text(0,29,m["calibration"]|"--");
          text(0,41,"Keep still, +Z up");
        } else if(!state.details) {
          text(0,15,m["activity"]|"--",2);
          formatted(0,34,"P %+.0f  R %+.0f",m["pitch"].as<double>(),m["roll"].as<double>());
          formatted(0,45,"A %.2fg",m["magnitude"].as<double>());
        } else {
          formatted(0,15,"AX %+.2f AY %+.2f",m["ax"].as<double>(),m["ay"].as<double>());
          formatted(0,26,"AZ %+.2fg",m["az"].as<double>());
          text(0,38,m["fallState"]|"--");
        }
        footer("T1 DETAILS",state.screen);
      } else if(state.screen==Screen::Temperature) {
        if(!t["celsius"].isNull() && !strcmp(t["quality"]|"UNAVAILABLE","GOOD")) {
          if(!state.details) { char number[16];snprintf(number,sizeof(number),"%.1f C",t["celsius"].as<double>());text(5,17,number,2); text(5,36,"LSM6DS3 internal"); }
          else { formatted(0,16,"%.1f F",t["celsius"].as<double>()*1.8+32); text(0,29,"Internal chip sensor"); text(0,42,"Not body temperature"); }
        } else { text(0,20,t["quality"]|"UNAVAILABLE");text(0,36,"Check LSM6DS3 / I2C"); }
        footer("T1 C / F DETAILS",state.screen);
      }
    }
  }

 private:
  Adafruit_SSD1306& oled_;
  int16_t offset_=0;
  void text(int x,int y,const char* value,uint8_t size=1) {
    oled_.setTextSize(size);oled_.setCursor(x+offset_,y);oled_.print(value);oled_.setTextSize(1);
  }
  void centered(const char* value,int y,uint8_t size=1) {
    text((128-int(strlen(value))*6*size)/2,y,value,size);
  }
  void formatted(int x,int y,const char* format,double a,double b=0) {
    char value[40];snprintf(value,sizeof(value),format,a,b);text(x,y,value);
  }
  void badge(int x,bool connected,char letter) {
    if(connected) oled_.fillRoundRect(x,0,10,9,2,SSD1306_WHITE);
    else oled_.drawRoundRect(x,0,10,9,2,SSD1306_WHITE);
    oled_.setTextColor(connected?SSD1306_BLACK:SSD1306_WHITE);
    oled_.setCursor(x+2,1);oled_.write(letter);oled_.setTextColor(SSD1306_WHITE);
  }
  void footer(const char* hint,Screen screen) {
    oled_.drawFastHLine(0,54,128,SSD1306_WHITE);oled_.setCursor(0,56);
    for(uint8_t i=0;i<16 && hint[i];++i) oled_.write(hint[i]);
    // The seven progress ticks occupy only the far right of the bottom row.
    for(uint8_t i=0;i<7;++i) {
      if(i==uint8_t(screen)) oled_.fillRect(101+i*4,58,3,4,SSD1306_WHITE);
      else oled_.drawPixel(102+i*4,61,SSD1306_WHITE);
    }
  }
  void icon(Screen screen,int x,int y) {
    if(screen==Screen::HeartRate || screen==Screen::Oxygen) {
      oled_.drawCircle(x+5,y+6,5,SSD1306_WHITE);oled_.drawCircle(x+15,y+6,5,SSD1306_WHITE);
      oled_.drawLine(x,y+8,x+10,y+21,SSD1306_WHITE);oled_.drawLine(x+20,y+8,x+10,y+21,SSD1306_WHITE);
      if(screen==Screen::Oxygen) { oled_.setCursor(x+7,y+6);oled_.print('O'); }
    } else if(screen==Screen::Motion) {
      oled_.drawCircle(x+10,y+3,3,SSD1306_WHITE);oled_.drawLine(x+10,y+6,x+10,y+15,SSD1306_WHITE);
      oled_.drawLine(x+2,y+11,x+18,y+8,SSD1306_WHITE);oled_.drawLine(x+10,y+15,x+3,y+22,SSD1306_WHITE);
      oled_.drawLine(x+10,y+15,x+18,y+22,SSD1306_WHITE);
    } else if(screen==Screen::Temperature) {
      oled_.drawRoundRect(x+7,y,7,16,3,SSD1306_WHITE);oled_.drawCircle(x+10,y+18,5,SSD1306_WHITE);
      oled_.drawLine(x+10,y+5,x+10,y+18,SSD1306_WHITE);
    } else if(screen==Screen::Alerts) {
      oled_.drawTriangle(x+10,y,x,y+21,x+20,y+21,SSD1306_WHITE);oled_.setCursor(x+8,y+10);oled_.print('!');
    } else {
      oled_.drawRoundRect(x,y,22,22,4,SSD1306_WHITE);
      for(int i=0;i<3;++i){oled_.drawFastHLine(x+4,y+5+i*6,14,SSD1306_WHITE);oled_.fillRect(x+6+i*3,y+3+i*6,3,5,SSD1306_WHITE);}
    }
  }
  void clockText(char* clock,size_t size,char* date,size_t dateSize,int32_t offset,bool compactClock=false) {
    time_t actual=time(nullptr);
    if(actual<1700000000) {
      snprintf(clock,size,"--:--");snprintf(date,dateSize,"TIME NOT SYNCED");return;
    }
    time_t local=actual+offset;tm t;gmtime_r(&local,&t);
    strftime(clock,size,compactClock?"%H:%M:%S":"%H:%M",&t);
    strftime(date,dateSize,"%a %d %b %Y",&t);
  }
  void home(uint32_t now,int32_t offset,bool fresh,Screen screen) {
    char clock[16],date[24];clockText(clock,sizeof(clock),date,sizeof(date),offset);
    centered(clock,17,3);centered(date,44);
    footer(fresh?"T1 APPS":"DATA WAIT / STALE",screen);
    (void)now;
  }
  void settings(const WatchState& s,WatchConnection link) {
    char line[32];
    const char* labels[]={"LIGHT", "SLEEP", "RAISE"};
    for(uint8_t i=0;i<3;++i) {
      text(0,14+i*11,i==uint8_t(s.setting)?(s.editing?"*":">"):" ");
      if(i==0) snprintf(line,sizeof(line),"%s %u%%",labels[i],unsigned(s.brightness)*100/255);
      else if(i==1) { if(s.timeoutMs) snprintf(line,sizeof(line),"%s %lus",labels[i],(unsigned long)(s.timeoutMs/1000));
                     else snprintf(line,sizeof(line),"%s ALWAYS ON",labels[i]); }
      else snprintf(line,sizeof(line),"%s %s",labels[i],s.raiseEnabled?"ON":"OFF");
      text(10,14+i*11,line);
    }
    snprintf(line,sizeof(line),"WIFI:%s SERVER:%s",link.connected?"OK":"--",link.server?"OK":"--");text(0,46,line);
    footer(s.editing?"T2 +/- T1 APPLY":"T1 EDIT",s.screen);
  }
  struct Alert { const char* title; const char* detail; };
  void alerts(const WatchState& s,const JsonDocument& d,bool fresh,WatchConnection link) {
    Alert list[8];uint8_t count=0;
    auto add=[&](const char* title,const char* detail){if(count<8)list[count++]={title,detail};};
    if(!fresh) add("DATA UNAVAILABLE","Waiting / stale");
    else {
      JsonObjectConst h=d["health"],m=d["motion"],t=d["temperature"],status=d["sensorStatus"];
      const char* fall=m["fallState"]|"NORMAL";
      if(!strcmp(fall,"FALL_ALERT")) add("FALL ALGORITHM","Check wearer now");
      else if(!strcmp(fall,"POSSIBLE_FALL") || !strcmp(fall,"VERIFYING")) add("CHECK MOTION",fall);
      if(strcmp(status["max30102"]|"UNAVAILABLE","OK")) add("MAX30102 OFFLINE","Check I2C wiring");
      else if(strcmp(h["quality"]|"UNAVAILABLE","GOOD")) add("OPTICAL QUALITY",h["quality"]|"UNAVAILABLE");
      if(strcmp(status["lsm6ds3"]|"UNAVAILABLE","OK")) add("LSM6DS3 OFFLINE","Raise disabled");
      else if(strcmp(m["quality"]|"UNAVAILABLE","GOOD")) add("IMU CALIBRATION",m["calibration"]|"UNAVAILABLE");
      if(strcmp(t["quality"]|"UNAVAILABLE","GOOD")) add("CHIP TEMP",t["quality"]|"UNAVAILABLE");
    }
    if(!link.enabled) add("WIFI OFFLINE","Check Serial/restart");
    else {
      if(!link.connected) add("WIFI OFFLINE","Monitoring locally");
      else if(!link.server) add("SERVER OFFLINE","Check laptop/server");
    }
    if(!count) {centered("NO LOCAL WARNINGS",22);centered("Current checks clear",38);}
    else {
      const Alert& selected=list[s.alertIndex%count];
      text(0,17,selected.title);text(0,31,selected.detail);
      char number[22];snprintf(number,sizeof(number),"%u / %u  T1 NEXT",unsigned(s.alertIndex%count)+1,count);text(0,44,number);
    }
    footer("LOCAL STATUS",s.screen);
  }
  void compact(const WatchState& s,const JsonDocument& d,bool fresh,WatchConnection link,uint32_t now,int32_t offset) {
    // Keep the explicitly supported 128x32 hardware usable without clipping 64px layouts.
    if(!s.opened) {text(0,14,"T1 OPEN / T2 NEXT");return;}
    if(s.screen==Screen::Home) {
      char clock[16],date[24];clockText(clock,sizeof(clock),date,sizeof(date),offset,true);
      text(0,13,clock);text(0,23,date);return;
    }
    if(s.screen==Screen::Settings) {
      char line[24];snprintf(line,sizeof(line),"L%u S%lus R%s%s",s.brightness,(unsigned long)(s.timeoutMs/1000),s.raiseEnabled?"ON":"OFF",s.editing?" *":"");text(0,13,line);
      text(0,23,"T1 EDIT/APPLY T2 +/-");return;
    }
    if(!fresh) {text(0,14,"WAITING / STALE");return;}
    if(s.screen==Screen::HeartRate || s.screen==Screen::Oxygen) {
      JsonVariantConst value=s.screen==Screen::HeartRate?d["health"]["bpm"]:d["health"]["spo2"];
      char line[22];if(value.isNull())snprintf(line,sizeof(line),"--");else snprintf(line,sizeof(line),"%.0f",value.as<double>());
      text(0,13,line);text(0,23,d["health"]["quality"]|"UNAVAILABLE");
    } else if(s.screen==Screen::Motion) {
      text(0,13,d["motion"]["activity"]|"UNAVAILABLE");text(0,23,d["motion"]["calibration"]|"--");
    } else if(s.screen==Screen::Temperature) {
      if(d["temperature"]["celsius"].isNull()) text(0,13,"-- C");
      else formatted(0,13,"%.1f C",d["temperature"]["celsius"].as<double>());
      text(0,23,"CHIP / NOT BODY");
    } else {text(0,13,d["motion"]["fallState"]|"--");text(0,23,link.connected?"WiFi ON":"WiFi OFF");}
    (void)now;
  }
};
} // namespace smartwatch
