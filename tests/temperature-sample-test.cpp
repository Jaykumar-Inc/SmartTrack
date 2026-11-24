#include "../esp32-firmware/SmartTrack/TemperatureSample.h"
#include <assert.h>
#include <stdio.h>
int main(){TemperatureSample t;assert(!t.valid(0));t.accept(0,0);assert(t.valid(0)&&t.celsius==25);t.accept(160,100);assert(t.celsius==35);t.accept(-160,100);assert(t.celsius==15);assert(t.valid(3099));assert(!t.valid(3100));t.invalidate();assert(!t.valid(100));t.accept(1200,1);assert(!t.valid(1));t.accept(16,0xfffffff0);assert(t.valid(20));assert(!t.valid(4000));puts("TEMPERATURE_TESTS_PASSED (conversion, negative raw, invalid range, disconnect, stale, millis rollover)");}
