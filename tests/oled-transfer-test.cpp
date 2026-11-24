#include "../esp32-firmware/SmartTrack/OledFrameTransfer.h"
#include <assert.h>
#include <stdio.h>
int main(){
 OledFrameTransfer frame(128,64);frame.start();int chunks=0;bool bottom=false;
 while(!frame.complete()){
  auto chunk=frame.next();assert(chunk.offset==chunks*16);assert(chunk.column==(chunks%8)*16);assert(chunk.page==chunks/8);assert(chunk.length==16);
  // A failed I2C transaction or unavailable mutex must never reset progress or skip bytes.
  frame.acknowledge(false);assert(frame.next().offset==chunk.offset);
  if(chunk.page>=4)bottom=true;frame.acknowledge(true);chunks++;
 }
 assert(chunks==64&&bottom);puts("PASS 128x64 covers all 1024 bytes including bottom half, with retries");
 OledFrameTransfer small(128,32);small.start();chunks=0;
 while(!small.complete()){assert(small.next().page<4);small.acknowledge(true);chunks++;}
 assert(chunks==32);puts("PASS 128x32 uses exactly four pages / 512 bytes");
 frame.start();assert(frame.next().offset==0);puts("OLED_TRANSFER_TESTS_PASSED");
}
