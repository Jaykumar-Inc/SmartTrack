#pragma once
#include <math.h>
#include <stdint.h>

// Hardware-independent algorithm: the same code is exercised by native regression tests.
enum class FallState { NORMAL, POSSIBLE_FALL, VERIFYING, FALL_ALERT, RECOVERY };
inline const char* fallName(FallState s) {
  switch(s) {case FallState::POSSIBLE_FALL:return "POSSIBLE_FALL";case FallState::VERIFYING:return "VERIFYING";
    case FallState::FALL_ALERT:return "FALL_ALERT";case FallState::RECOVERY:return "RECOVERY";default:return "NORMAL";}
}
struct MotionConfig {
  float sensitivity=1.0f, lowG=0.6f, impactG=2.5f, rotationDps=180.0f, rotationDegrees=45.0f;
  float orientationDegrees=35.0f, stillG=0.12f, stillDps=12.0f, movementG=0.10f;
  uint32_t impactWindowMs=1500, verificationMs=6500, inactivityMs=2000, alertHoldMs=10000, cooldownMs=20000;
};
struct MotionSample {float ax,ay,az,gx,gy,gz;};
class MotionEngine {
 public:
  MotionConfig config;
  FallState state=FallState::NORMAL;
  float ax=0,ay=0,az=1,gx=0,gy=0,gz=0,magnitude=1,rawMagnitude=1,pitch=0,roll=0,rotation=0;
  bool calibrated=false,moving=false,inactive=false,impact=false;
  uint32_t eventId=0,impactCount=0;
  const char* calibration="KEEP_STILL_FLAT";
  void beginCalibration(){calibrated=false;calibration="KEEP_STILL_FLAT";count=0;for(int i=0;i<6;i++)sum[i]=sumSq[i]=0;}
  void cancel(uint32_t now){if(state!=FallState::NORMAL&&state!=FallState::RECOVERY){state=FallState::RECOVERY;entered=now;stillStarted=0;}}
  void update(const MotionSample& s,uint32_t now) {
    float values[]={s.ax,s.ay,s.az,s.gx,s.gy,s.gz};for(float v:values)if(!isfinite(v))return;
    float raw=sqrtf(s.ax*s.ax+s.ay*s.ay+s.az*s.az),speed=sqrtf(s.gx*s.gx+s.gy*s.gy+s.gz*s.gz);
    if(!calibrated){
      // One-position calibration requires a stationary, approximately flat +Z-up board.
      if(fabsf(raw-1)>0.15f||speed>8||s.az<0.85f||fabsf(s.ax)>0.2f||fabsf(s.ay)>0.2f){beginCalibration();calibration="MOVE_OR_NOT_FLAT";return;}
      for(int i=0;i<6;i++){sum[i]+=values[i];sumSq[i]+=values[i]*values[i];}count++;
      if(count<150)return;
      for(int i=0;i<6;i++){float variance=sumSq[i]/count-powf(sum[i]/count,2);if(variance>(i<3?0.001f:0.5f)){beginCalibration();calibration="TOO_NOISY_RETRY";return;}}
      for(int i=0;i<6;i++)offset[i]=sum[i]/count;offset[2]-=1;calibrated=true;calibration="CALIBRATED";initialized=false;lastMoving=now;
    }
    float x=s.ax-offset[0],y=s.ay-offset[1],z=s.az-offset[2];
    float rx=s.gx-offset[3],ry=s.gy-offset[4],rz=s.gz-offset[5];
    rawMagnitude=sqrtf(x*x+y*y+z*z);
    if(!initialized){ax=x;ay=y;az=z;gx=rx;gy=ry;gz=rz;initialized=true;previous=now;}
    float dt=(now-previous)/1000.0f;if(dt>0.1f)dt=0.1f;previous=now;
    const float alpha=0.25f;ax+=alpha*(x-ax);ay+=alpha*(y-ay);az+=alpha*(z-az);gx+=alpha*(rx-gx);gy+=alpha*(ry-gy);gz+=alpha*(rz-gz);
    magnitude=sqrtf(ax*ax+ay*ay+az*az);speed=sqrtf(rx*rx+ry*ry+rz*rz);
    pitch=atan2f(-ax,sqrtf(ay*ay+az*az))*57.2957795f;roll=atan2f(ay,az)*57.2957795f;
    float sensitivity=config.sensitivity<0.5f?0.5f:config.sensitivity>2?2:config.sensitivity;
    moving=fabsf(rawMagnitude-1)>config.movementG/sensitivity||speed>15/sensitivity;
    if(moving)lastMoving=now;inactive=now-lastMoving>=config.inactivityMs;
    bool hit=rawMagnitude>fmaxf(1.5f,config.impactG/sensitivity);
    impact=hit||now-lastImpact<1000;
    if(hit&&(impactCount==0||now-lastImpact>1000)){impactCount++;lastImpact=now;}
    if(state==FallState::NORMAL){
      if(fabsf(rawMagnitude-1)<0.12f&&speed<15){baseX=ax;baseY=ay;baseZ=az;}
      if(rawMagnitude<fminf(0.85f,config.lowG*sensitivity)||(speed>config.rotationDps/sensitivity&&fabsf(rawMagnitude-1)>0.3f)){
        state=FallState::POSSIBLE_FALL;entered=now;rotation=0;eventId++;stillStarted=0;
      }
    }else if(state==FallState::POSSIBLE_FALL){
      rotation+=speed*dt;
      if(hit){state=FallState::VERIFYING;entered=now;stillStarted=0;}
      else if(now-entered>config.impactWindowMs){state=FallState::RECOVERY;entered=now;}
    }else if(state==FallState::VERIFYING){
      rotation+=speed*dt;
      float denom=sqrtf(baseX*baseX+baseY*baseY+baseZ*baseZ)*magnitude;
      float cosine=denom>0.01f?(baseX*ax+baseY*ay+baseZ*az)/denom:1;
      cosine=cosine<-1?-1:cosine>1?1:cosine;
      float change=acosf(cosine)*57.2957795f;
      bool still=fabsf(rawMagnitude-1)<config.stillG&&speed<config.stillDps;
      if(still){if(!stillStarted)stillStarted=now;}else stillStarted=0;
      if(stillStarted&&now-stillStarted>=config.inactivityMs&&rotation>=config.rotationDegrees/sensitivity&&change>=config.orientationDegrees/sensitivity){state=FallState::FALL_ALERT;entered=now;}
      else if(now-entered>config.verificationMs){state=FallState::RECOVERY;entered=now;}
    }else if(state==FallState::FALL_ALERT&&now-entered>=config.alertHoldMs){state=FallState::RECOVERY;entered=now;}
    else if(state==FallState::RECOVERY&&now-entered>=config.cooldownMs){state=FallState::NORMAL;entered=now;}
  }
 private:
  float offset[6]={},sum[6]={},sumSq[6]={};int count=0;bool initialized=false;
  float baseX=0,baseY=0,baseZ=1;uint32_t previous=0,entered=0,stillStarted=0,lastMoving=0,lastImpact=0;
};
