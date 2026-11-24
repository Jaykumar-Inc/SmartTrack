#include "../esp32-firmware/SmartTrack/MotionEngine.h"
#include <assert.h>
#include <stdio.h>

uint32_t tick=0;
void feed(MotionEngine& engine,MotionSample sample,int samples){for(int i=0;i<samples;i++){tick+=20;engine.update(sample,tick);}}
void calibrate(MotionEngine& e){feed(e,{0.01f,-0.02f,1.03f,0.3f,-0.4f,0.2f},151);assert(e.calibrated);assert(fabsf(e.magnitude-1)<0.01f);}
int main(){
 MotionEngine e;calibrate(e);puts("PASS stationary offsets preserve gravity and remove gyro drift");
 feed(e,{0.01f,-0.02f,1.03f,0.3f,-0.4f,0.2f},200);assert(e.inactive);assert(e.state==FallState::NORMAL);
 feed(e,{0,0,3.5f,0,0,0},1);assert(e.impactCount==1);assert(e.state==FallState::NORMAL);puts("PASS single impact is not a fall");
 feed(e,{0,0,1,0,0,0},100);feed(e,{0,0,0.1f,0,0,0},1);assert(e.state==FallState::POSSIBLE_FALL);
 feed(e,{0,0,0.1f,300,0,0},8);feed(e,{0,0,3.5f,300,0,0},1);assert(e.state==FallState::VERIFYING);
 feed(e,{1,0,0,300,0,0},8);feed(e,{1,0,0,0,0,0},130);assert(e.state==FallState::FALL_ALERT);auto id=e.eventId;
 feed(e,{1,0,0,0,0,0},100);assert(e.eventId==id);puts("PASS rotation + impact + orientation change + inactivity required; event ID stable");
 e.cancel(tick);assert(e.state==FallState::RECOVERY);feed(e,{0,0,0.1f,300,0,0},10);assert(e.eventId==id);puts("PASS manual cancellation and cooldown prevent repeated events");
 feed(e,{0,0,1,0,0,0},1100);assert(e.state==FallState::NORMAL);
 feed(e,{0,0,0.1f,0,0,0},1);feed(e,{0,0,1,0,0,0},100);assert(e.state==FallState::RECOVERY);puts("PASS missing impact times out without fall alert");
 MotionEngine moving;feed(moving,{0.4f,0,0.7f,50,0,0},250);assert(!moving.calibrated);puts("PASS moving/tilted calibration is rejected");
 MotionEngine sensitive;sensitive.config.sensitivity=2;calibrate(sensitive);feed(sensitive,{0,0,1,0,0,0},200);assert(sensitive.state==FallState::NORMAL);puts("PASS maximum sensitivity does not classify normal gravity as free fall");
 MotionEngine rollover;tick=0xffffe000U;calibrate(rollover);feed(rollover,{0,0,1,0,0,0},500);assert(rollover.state==FallState::NORMAL);puts("PASS monotonic timer wrap does not create a fall");
 puts("MOTION_ENGINE_TESTS_PASSED");
}
