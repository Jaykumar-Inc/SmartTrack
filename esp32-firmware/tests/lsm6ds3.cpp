#include <Arduino.h>
#include <Wire.h>
#include "SparkFunLSM6DS3.h"
LSM6DS3 imu(I2C_MODE,0x6B);bool ready=false;
void setup(){Serial.begin(115200);Wire.begin(21,22);Wire.setClock(100000);imu.settings.accelRange=8;imu.settings.gyroRange=500;ready=imu.begin()==0;Serial.println(ready?"LSM6DS3 0x6B initialized":"LSM6DS3 0x6B not found");}
void loop(){if(ready){float x=imu.readFloatAccelX(),y=imu.readFloatAccelY(),z=imu.readFloatAccelZ();Serial.printf("A(g) %.4f %.4f %.4f magnitude=%.4f G(deg/s) %.3f %.3f %.3f T(C) %.2f\n",x,y,z,sqrtf(x*x+y*y+z*z),imu.readFloatGyroX(),imu.readFloatGyroY(),imu.readFloatGyroZ(),imu.readTempC());}delay(100);}
