#include "../esp32-firmware/SmartTrack/WatchState.h"
#include <assert.h>
#include <stdio.h>
#include <limits>
using namespace smartwatch;

struct Rig {
  TouchControls input;
  WatchState watch;
  uint32_t now;
  unsigned shorts1=0,longs1=0,shorts2=0,longs2=0,chords=0;
  Rig(uint32_t start=0):now(start){watch.begin(now);}
  void step(bool a,bool b,uint32_t duration) {
    for(uint32_t i=0;i<duration;i+=5) {
      now+=5;uint8_t e=input.update(a,b,now);
      shorts1+=bool(e&Touch1Short);longs1+=bool(e&Touch1Long);
      shorts2+=bool(e&Touch2Short);longs2+=bool(e&Touch2Long);chords+=bool(e&TouchBoth);
      watch.touch(e,input.held(),now);watch.tick(now);
    }
  }
  void tap(bool first){step(first,!first,100);step(false,false,60);}
};

int main() {
  {Rig r;r.step(false,false,100);r.step(true,false,20);r.step(false,false,80);
   assert(!r.shorts1&&!r.longs1&&r.watch.screen==Screen::Home);puts("PASS debounce rejects short noise");}
  {Rig r;r.tap(true);assert(r.shorts1==1&&!r.longs1);assert(r.watch.screen==Screen::HeartRate&&!r.watch.opened);
   r.tap(true);assert(r.watch.opened);puts("PASS short select opens launcher and selected app");}
  {Rig r;r.tap(false);assert(r.watch.screen==Screen::HeartRate);
   r.step(false,true,900);r.step(false,false,70);
   assert(r.longs2==1&&r.shorts2==1&&r.watch.screen==Screen::Home);puts("PASS next and long previous do not double-fire");}
  {Rig r;r.tap(false);r.step(true,false,1700);r.step(false,false,60);
   assert(r.longs1==1&&!r.shorts1&&r.watch.screen==Screen::Home);puts("PASS long home fires once without release selection");}
  {Rig r;r.step(true,true,1600);assert(r.chords==1&&!r.watch.awake);
   r.step(true,false,800);r.step(false,false,80);
   assert(!r.shorts1&&!r.longs1&&!r.shorts2&&!r.longs2&&!r.watch.awake);
   r.step(true,true,100);r.step(false,false,60);
   assert(r.chords==2&&r.watch.awake);puts("PASS chords suppress individual actions and toggle once per gesture");}
  {Rig r;r.step(false,false,15100);assert(!r.watch.awake);
   r.tap(true);assert(r.watch.awake&&r.watch.screen==Screen::Home);
   r.tap(true);assert(r.watch.screen==Screen::HeartRate);puts("PASS first touch wakes without accidentally opening an app");}
  {Rig r;r.step(false,false,15100);assert(!r.watch.awake);
   r.step(true,false,60);r.step(true,true,100);r.step(false,false,80);
   assert(r.watch.awake&&r.chords==1);puts("PASS staggered chord wakes sleeping screen instead of toggling it off again");}
  {Rig r;r.watch.screen=Screen::Settings;r.watch.opened=true;
   r.tap(true);assert(r.watch.editing);
   uint8_t previous=r.watch.brightness;r.tap(false);assert(r.watch.brightness>previous);
   r.tap(true);assert(!r.watch.editing&&r.watch.setting==Setting::Timeout);
   r.tap(true);r.tap(false);assert(r.watch.timeoutMs==30000);
   r.tap(true);assert(r.watch.setting==Setting::Raise);r.tap(true);r.tap(false);assert(!r.watch.raiseEnabled);
   puts("PASS settings edit changes contrast timeout and raise with explicit apply");}
  {Rig r(0xfffffff0U);r.tap(false);assert(r.shorts2==1);
   r.step(false,false,15100);assert(!r.watch.awake);puts("PASS debounce and display timeout survive millis rollover");}
  {Rig r;r.watch.timeoutMs=0;r.step(false,false,90000);assert(r.watch.awake);
   r.watch.timeoutMs=500;r.step(true,false,1500);assert(r.watch.awake);puts("PASS always-on option and held-touch activity");}
  {WatchState w;w.begin(0);w.touch(TouchBegin|TouchBoth,true,100);assert(!w.awake);
   w.raise(500);assert(!w.awake);w.raise(2200);assert(w.awake&&w.screen==Screen::Home);
   w.touch(TouchIdle,false,2300);puts("PASS raise wake guard respects recent manual sleep");}
  {RaiseToWake r;uint32_t t=0;for(int i=0;i<20;++i){t+=20;assert(!r.update({1,0,0,0,0,0,t}));}
   t+=20;assert(!r.update({0,0,1,40,0,0,t}));bool woke=false;
   for(int i=0;i<12;++i){t+=20;woke|=r.update({0,0,1,0,0,0,t});}assert(woke);
   for(int i=0;i<50;++i){t+=20;assert(!r.update({0,0,1,0,0,0,t}));}
   puts("PASS real lower rotate face-up gesture wakes once; static orientation does not retrigger");}
  {RaiseToWake r;uint32_t t=0;for(int i=0;i<20;++i){t+=20;r.update({1,0,0,0,0,0,t});}
   for(int i=0;i<20;++i){t+=20;assert(!r.update({0,0,1,0,0,0,t}));}
   float bad=std::numeric_limits<float>::quiet_NaN();t+=20;assert(!r.update({bad,0,1,40,0,0,t}));
   t+=1000;assert(!r.update({0,0,1,40,0,0,t}));puts("PASS raise rejects absent rotation invalid samples and disconnected sample gaps");}
  {RaiseToWake r;uint32_t t=0;for(int i=0;i<20;++i){t+=20;r.update({1,0,0,0,0,0,t});}
   for(int i=0;i<110;++i){t+=20;assert(!r.update({0,0,1,0,0,0,t}));}
   for(int i=0;i<12;++i){t+=20;assert(!r.update({0,0,1,50,0,0,t}));}
   puts("PASS an old lowered pose cannot arm a much later unrelated movement");}
  puts("WATCH_CONTROLS_TESTS_PASSED (14 cases)");
}
