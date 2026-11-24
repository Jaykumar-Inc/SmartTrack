#pragma once
#include <stdint.h>

// Pure frame-progress bookkeeping, shared by the hardware worker and host regression test.
// Advance only after an acknowledged write; retries target exactly the same display bytes.
class OledFrameTransfer {
 public:
  struct Chunk { uint16_t offset; uint8_t column,page,length; };
  OledFrameTransfer(uint8_t width,uint8_t height):width_(width),bytes_(uint16_t(width)*height/8){}
  void start(){offset_=0;}
  bool complete()const{return offset_>=bytes_;}
  Chunk next()const{
    uint8_t column=offset_%width_;
    uint16_t remaining=bytes_-offset_;
    uint8_t length=remaining<16?remaining:16;
    if(length>width_-column)length=width_-column;
    return {offset_,column,uint8_t(offset_/width_),length};
  }
  void acknowledge(bool success){if(success&&!complete())offset_+=next().length;}
 private:
  uint8_t width_;
  uint16_t bytes_,offset_=0;
};
