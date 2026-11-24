#include <Arduino.h>
#include <Wire.h>
#include <MAX30105.h>
#include <spo2_algorithm.h>
MAX30105 sensor;uint32_t ir[100],red[100];int count=0;bool ready=false;
void setup(){Serial.begin(115200);Wire.begin(21,22);ready=sensor.begin(Wire,I2C_SPEED_STANDARD);if(ready)sensor.setup(60,4,2,100,411,4096);else Serial.println("MAX30102 not found");}
void loop(){if(!ready){delay(100);return;}sensor.check();while(sensor.available()){ir[count]=sensor.getFIFOIR();red[count++]=sensor.getFIFORed();sensor.nextSample();if(count==100){int32_t spo,bpm;int8_t sv,bv;maxim_heart_rate_and_oxygen_saturation(ir,100,red,&spo,&sv,&bpm,&bv);Serial.printf("IR=%lu BPM=%ld valid=%d SpO2=%ld valid=%d (educational estimate)\n",(unsigned long)ir[99],(long)bpm,bv,(long)spo,sv);count=0;}}}
