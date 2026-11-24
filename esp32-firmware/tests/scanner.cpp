#include <Arduino.h>
#include <Wire.h>
void setup(){Serial.begin(115200);Wire.begin(21,22);Wire.setClock(100000);}
void loop(){for(uint8_t a=1;a<127;a++){Wire.beginTransmission(a);if(Wire.endTransmission()==0)Serial.printf("Found I2C 0x%02X\n",a);}delay(3000);}
