# Circuit connections

Disconnect USB and external power before changing wires. Remove the old UART location module completely, including its power and signal leads. GPIO26 and GPIO27 are unused by this release.

| Module signal | ESP32 connection |
|---|---|
| MAX30102 SDA | GPIO21 |
| MAX30102 SCL | GPIO22 |
| GY-LSM6DS3 SDA | GPIO21 |
| GY-LSM6DS3 SCL | GPIO22 |
| SSD1306 OLED SDA | GPIO21 |
| SSD1306 OLED SCL | GPIO22 |
| TTP223 sensor 1 OUT | GPIO32 |
| TTP223 sensor 2 OUT | GPIO33 |
| Both TTP223 VCC | 3.3V |
| All module GND | ESP32 GND, common ground |
| IMU, OLED, MAX30102 supply | Verify the actual breakout's VIN rating; use 3.3V-compatible I2C logic |

```text
ESP32 GPIO21 --- MAX30102 SDA --- GY-LSM6DS3 SDA --- OLED SDA
ESP32 GPIO22 --- MAX30102 SCL --- GY-LSM6DS3 SCL --- OLED SCL
ESP32 GPIO32 <-- TTP223 #1 OUT
ESP32 GPIO33 <-- TTP223 #2 OUT
ESP32 3V3 ----- both TTP223 VCC
ESP32 GND ----- every module GND
```

The 100 kHz I2C bus expects MAX30102 at 0x57, LSM6DS3 at 0x6B and SSD1306 at 0x3C. Keep wires short and check that all pull-ups use 3.3V. The bare MAX30102 chip needs a 1.8V core and a separate LED supply, so do not infer an arbitrary breakout's VIN rating from the chip name. Check the supplier schematic.

## Temperature needs no extra wires

The GY-LSM6DS3 contains the temperature channel. For the original LSM6DS3 with WHO_AM_I 0x69, signed OUT_TEMP_H/L raw data converts using `25 + raw/16` degrees Celsius. The firmware checks data-ready, register access, two received bytes, plausible operating range and sample age. A stale/disconnected channel transmits a null value.

This measures chip die temperature. Processor/board heat, enclosure and self-heating affect it. It is neither a clinical body thermometer nor a calibrated ambient thermometer. A different LSM6DS3 variant needs its own confirmed conversion and identity support.

Sources: [ST AN4650](https://community.st.com/ysqtg83639/attachments/ysqtg83639/mems-sensors-forum/21052/2/AN4650.PDF), [Arduino LSM6DS3 implementation](https://github.com/arduino-libraries/Arduino_LSM6DS3/blob/master/src/LSM6DS3.cpp), [MAX30102 manufacturer](https://www.analog.com/en/products/max30102.html).
