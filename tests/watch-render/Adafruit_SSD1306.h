#pragma once
// Test-only raster adapter. Production uses the real Adafruit SSD1306 library.
// It lets the actual WatchRenderer run on the host without an attached OLED.
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <glcdfont.c> // Existing Adafruit GFX dependency, not a new production font.
#define SSD1306_WHITE 1
#define SSD1306_BLACK 0
class Adafruit_SSD1306 {
 public:
  uint8_t pixels[64][128] = {};
  bool clippedText = false;
  int height() const {return 64;}
  void clearDisplay(){memset(pixels,0,sizeof(pixels));clippedText=false;}
  void setTextWrap(bool){}
  void setTextSize(uint8_t s){size_=s;}
  void setTextColor(uint16_t c){color_=c;}
  void setCursor(int x,int y){x_=x;y_=y;}
  void drawPixel(int x,int y,uint16_t c){if(x>=0&&x<128&&y>=0&&y<64)pixels[y][x]=bool(c);}
  void fillRect(int x,int y,int w,int h,uint16_t c){for(int row=y;row<y+h;++row)for(int col=x;col<x+w;++col)drawPixel(col,row,c);}
  void drawFastHLine(int x,int y,int w,uint16_t c){fillRect(x,y,w,1,c);}
  void drawLine(int x,int y,int endX,int endY,uint16_t c){
    int dx=abs(endX-x),sx=x<endX?1:-1,dy=-abs(endY-y),sy=y<endY?1:-1,err=dx+dy;
    for(;;){drawPixel(x,y,c);if(x==endX&&y==endY)break;int twice=2*err;if(twice>=dy){err+=dy;x+=sx;}if(twice<=dx){err+=dx;y+=sy;}}
  }
  void drawCircle(int x,int y,int radius,uint16_t c){
    int dx=radius,dy=0,error=1-radius;
    while(dx>=dy){for(int sx : {-1,1})for(int sy : {-1,1}){drawPixel(x+sx*dx,y+sy*dy,c);drawPixel(x+sx*dy,y+sy*dx,c);}
      ++dy;if(error<0)error+=2*dy+1;else{--dx;error+=2*(dy-dx)+1;}}
  }
  void drawTriangle(int x,int y,int a,int b,int u,int v,uint16_t c){drawLine(x,y,a,b,c);drawLine(a,b,u,v,c);drawLine(u,v,x,y,c);}
  void drawRoundRect(int x,int y,int w,int h,int r,uint16_t c){
    drawFastHLine(x+r,y,w-2*r,c);drawFastHLine(x+r,y+h-1,w-2*r,c);
    drawLine(x,y+r,x,y+h-r-1,c);drawLine(x+w-1,y+r,x+w-1,y+h-r-1,c);
    // Small corners are represented by diagonal connections in this test adapter.
    drawLine(x,y+r,x+r,y,c);drawLine(x+w-r-1,y,x+w-1,y+r,c);
    drawLine(x,y+h-r-1,x+r,y+h-1,c);drawLine(x+w-r-1,y+h-1,x+w-1,y+h-r-1,c);
  }
  void fillRoundRect(int x,int y,int w,int h,int,uint16_t c){fillRect(x,y,w,h,c);}
  void print(const char* s){while(*s)write(uint8_t(*s++));}
  void print(char c){write(uint8_t(c));}
  void write(uint8_t character){
    if(x_<0||x_+6*size_>128||y_<0||y_+8*size_>64)clippedText=true;
    for(int col=0;col<5;++col){uint8_t bits=font[unsigned(character)*5+col];
      for(int row=0;row<8;++row)if(bits&(1<<row))fillRect(x_+col*size_,y_+row*size_,size_,size_,color_);}
    x_+=6*size_;
  }
  void save(const char* path){
    FILE* f=fopen(path,"wb");if(!f)abort();fprintf(f,"P4\n128 64\n");
    for(int y=0;y<64;++y)for(int x=0;x<128;x+=8){uint8_t value=0;for(int b=0;b<8;++b)value|=pixels[y][x+b]<<(7-b);fputc(value,f);}fclose(f);
  }
 private:
  int x_=0,y_=0;uint8_t size_=1;uint16_t color_=SSD1306_WHITE;
};
