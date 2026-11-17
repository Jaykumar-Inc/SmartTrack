"""Build original SmartTrack vector-derived Windows ICO assets; no external image dependency."""
import math, struct, zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'design-system/icons';DEST.mkdir(parents=True,exist_ok=True)
def chunk(tag,data):return struct.pack('>I',len(data))+tag+data+struct.pack('>I',zlib.crc32(tag+data)&0xffffffff)
def distance(px,py,x1,y1,x2,y2):
    dx=x2-x1;dy=y2-y1;t=max(0,min(1,((px-x1)*dx+(py-y1)*dy)/(dx*dx+dy*dy)))
    return math.hypot(px-x1-t*dx,py-y1-t*dy)
def png(size):
    points=[(8,24),(18,24),(22,12),(28,36),(32,24),(40,24)];rows=[]
    for y in range(size):
        row=bytearray([0])
        for x in range(size):
            px=(x+.5)*48/size;py=(y+.5)*48/size
            d=min(distance(px,py,*a,*b) for a,b in zip(points,points[1:]));coverage=max(0,min(1,(1.5-d)*size/48+.5))
            base=(11,16,22);accent=(76,183,165);row.extend(round(b+(a-b)*coverage) for b,a in zip(base,accent));row.append(255)
        rows.append(row)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',size,size,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows),9))+chunk(b'IEND',b'')
sizes=[16,32,48,64,128,256];images=[png(s) for s in sizes];offset=6+len(sizes)*16;directory=[]
for size,data in zip(sizes,images):directory.append(struct.pack('<BBBBHHII',size%256,size%256,0,0,1,32,len(data),offset));offset+=len(data)
(DEST/'smarttrack.ico').write_bytes(struct.pack('<HHH',0,1,len(sizes))+b''.join(directory)+b''.join(images))
(DEST/'smarttrack.png').write_bytes(images[-1])
(DEST/'smarttrack.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><path fill="#0B1016" d="M0 0h48v48H0z"/><path fill="none" stroke="#4CB7A5" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" d="M8 24h10l4-12 6 24 4-12h8"/></svg>\n',encoding='utf-8')
print('Generated SmartTrack vector, PNG and six-size Windows icon')
