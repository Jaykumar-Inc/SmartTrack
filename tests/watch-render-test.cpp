#include <initializer_list>
#include <time.h>
#ifdef _WIN32
inline tm* gmtime_r(const time_t* source,tm* target){return gmtime_s(target,source)==0?target:nullptr;}
#endif
#include "../esp32-firmware/SmartTrack/WatchRenderer.h"
#include <assert.h>
using namespace smartwatch;
int main(){
  // Synthetic unavailable-state fixtures only. These are layout tests, not sensor captures.
  Adafruit_SSD1306 oled;WatchRenderer renderer(oled);WatchState state;JsonDocument data;
  data["health"]["quality"]="NO_FINGER";data["health"]["bpm"]=nullptr;data["health"]["spo2"]=nullptr;
  data["motion"]["quality"]="CALIBRATING";data["motion"]["calibration"]="MOVE_OR_NOT_FLAT";
  data["motion"]["activity"]="UNAVAILABLE";data["motion"]["fallState"]="NORMAL";
  data["temperature"]["quality"]="UNAVAILABLE";data["temperature"]["celsius"]=nullptr;
  data["sensorStatus"]["max30102"]="OK";data["sensorStatus"]["lsm6ds3"]="OK";
  int count=0;
  for(int screen=0;screen<int(Screen::Count);++screen){
    for(int mode=0;mode<3;++mode){
      state.screen=Screen(screen);state.opened=mode!=0;state.details=mode==2;
      renderer.draw(state,data,1000,1000,{true,false,false},19800,400);
      if(oled.clippedText)fprintf(stderr,"Clipped text: screen %d mode %d\n",screen,mode);
      assert(!oled.clippedText);++count;
      if(mode==1){char path[128];snprintf(path,sizeof(path),"SmartTrack/tests/watch-render/screen-%d.pbm",screen);oled.save(path);}
    }
  }
  JsonDocument missing;state.screen=Screen::Temperature;state.opened=true;
  renderer.draw(state,missing,0,5000,{false,false,false},19800,400);assert(!oled.clippedText);++count;
  renderer.draw(state,data,0,5000,{true,false,false},19800,400);assert(!oled.clippedText);++count;
  state.screen=Screen::Alerts;data["motion"]["fallState"]="FALL_ALERT";
  renderer.draw(state,data,1000,1000,{true,true,true},19800,400);assert(!oled.clippedText);++count;
  for(int index=0;index<8;index++){
    state.alertIndex=index;renderer.draw(state,data,1000,1000,{false,false,false},19800,400);assert(!oled.clippedText);++count;
  }
  state.screen=Screen::Settings;state.editing=true;
  for(int row=0;row<3;row++)for(int linked=0;linked<2;linked++){
    state.setting=Setting(row);renderer.draw(state,data,1000,1000,{true,bool(linked),bool(linked)},19800,400);assert(!oled.clippedText);++count;
  }
  state.screen=Screen::Settings;state.opened=true;
  renderer.draw(state,data,1000,1000,{true,true,true},19800,400);assert(!oled.clippedText);++count;
  state.screen=Screen::Temperature;state.opened=true;
  data["temperature"]["celsius"]=-40.0;data["temperature"]["quality"]="GOOD";
  for(int details=0;details<2;details++){state.details=details;renderer.draw(state,data,1000,1000,{true,true,true},19800,400);assert(!oled.clippedText);++count;}
  printf("WATCH_RENDER_TESTS_PASSED (%d layouts; no text outside 128x64)\n",count);
}
