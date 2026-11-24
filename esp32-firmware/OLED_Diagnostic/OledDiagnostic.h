#pragma once
#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_SSD1306.h>
#ifndef OLED_HEIGHT
#define OLED_HEIGHT 64
#endif
Adafruit_SSD1306 screen(128,OLED_HEIGHT,&Wire,-1,100000,100000);
bool ready=false;uint32_t last=0;
void setup(){
 Serial.begin(115200);Wire.begin(21,22);Wire.setClock(100000);Wire.setTimeOut(50);
 Wire.beginTransmission(0x3C);if(Wire.endTransmission()!=0){Serial.println("OLED 0x3C not responding: check SDA21/SCL22, power and ground");return;}
 ready=screen.begin(SSD1306_SWITCHCAPVCC,0x3C);if(!ready){Serial.println("OLED framebuffer allocation failed");return;}
 screen.setTextWrap(false);Serial.printf("OLED full-screen test: SSD1306 128x%d. All four corners, bottom line and every band must appear.\n",OLED_HEIGHT);
}
void loop(){
 if(!ready){delay(100);return;}
 int phase=(millis()/3000)%3;screen.clearDisplay();
 if(phase==0){screen.fillScreen(SSD1306_WHITE);}
 if(phase==1){screen.drawRect(0,0,128,OLED_HEIGHT,SSD1306_WHITE);screen.setTextSize(1);screen.setTextColor(SSD1306_WHITE);screen.setCursor(3,2);screen.print("TOP L          R");screen.setCursor(3,OLED_HEIGHT-10);screen.print("BOTTOM L       R");screen.drawFastHLine(0,OLED_HEIGHT/2,128,SSD1306_WHITE);}
 if(phase==2){for(int page=0;page<OLED_HEIGHT/8;page++){if(page%2==0)screen.fillRect(0,page*8,128,8,SSD1306_WHITE);screen.setTextColor(page%2==0?SSD1306_BLACK:SSD1306_WHITE);screen.setTextSize(1);screen.setCursor(3,page*8);screen.printf("Row %d..%d",page*8,page*8+7);}}
 screen.display();if(millis()-last>3000){last=millis();Serial.printf("Pattern=%d geometry=128x%d\n",phase,OLED_HEIGHT);}delay(100);
}
