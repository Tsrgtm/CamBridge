// SPDX-License-Identifier: GPL-2.0-or-later
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vector>
#include <cstring>
#include <algorithm>
#include "shared-memory-queue.h"
#include "tiny-nv12-scale.h"

// A single replaceable frame, never a playback queue. Skip a write if a reader
// is copying; readers release the mutex before any potentially expensive scale.
static constexpr size_t capacity = 4096u * 4096u * 3u / 2u;
struct Header { uint32_t magic, state, width, height; uint64_t interval, timestamp, sequence; };
struct video_queue { HANDLE map = nullptr, mutex = nullptr, producer = nullptr; Header *header = nullptr; bool writer = false; std::vector<uint8_t> copy, fitted; };
static video_queue_t *openQueue(bool writer) {
    auto *q = new video_queue;
    if (writer) {
        q->producer = CreateMutexW(nullptr, TRUE, L"Local\\CamBridgeProducerV1");
        if (!q->producer || GetLastError() == ERROR_ALREADY_EXISTS) { video_queue_close(q); return nullptr; }
    }
    q->mutex = CreateMutexW(nullptr, FALSE, L"Local\\CamBridgeFrameLockV1");
    q->map = writer ? CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, DWORD(capacity + sizeof(Header)), L"Local\\CamBridgeVideoV1")
                    : OpenFileMappingW(FILE_MAP_READ, FALSE, L"Local\\CamBridgeVideoV1");
    if (q->map) { q->header = static_cast<Header *>(MapViewOfFile(q->map, writer ? FILE_MAP_ALL_ACCESS : FILE_MAP_READ, 0, 0, capacity + sizeof(Header))); }
    if (!q->mutex || !q->header) { video_queue_close(q); return nullptr; }
    q->writer = writer;
    return q;
}
extern "C" video_queue_t *video_queue_create(uint32_t w, uint32_t h, uint64_t interval) {
    if (!w || !h || (w | h) & 1 || w > 4096 || h > 4096 || size_t(w) * h * 3 / 2 > capacity) { return nullptr; }
    auto *q = openQueue(true); if (!q) { return nullptr; }
    DWORD lock = WaitForSingleObject(q->mutex, 100);
    if (lock != WAIT_OBJECT_0 && lock != WAIT_ABANDONED) { video_queue_close(q); return nullptr; }
    *q->header = {0x43424631, SHARED_QUEUE_STATE_STARTING, w, h, interval, 0, 0};
    ReleaseMutex(q->mutex); return q;
}
extern "C" video_queue_t *video_queue_open() { return openQueue(false); }
extern "C" void video_queue_close(video_queue_t *q) {
    if (!q) { return; }
    if (q->writer && q->header) { InterlockedExchange(reinterpret_cast<volatile LONG *>(&q->header->state), SHARED_QUEUE_STATE_STOPPING); }
    if (q->header) { UnmapViewOfFile(q->header); }
    if (q->map) { CloseHandle(q->map); }
    if (q->mutex) { CloseHandle(q->mutex); }
    if (q->producer) { CloseHandle(q->producer); }
    delete q;
}
extern "C" void video_queue_get_info(video_queue_t *q, uint32_t *w, uint32_t *h, uint64_t *interval) { *w=q->header->width; *h=q->header->height; *interval=q->header->interval; }
extern "C" queue_state video_queue_state(video_queue_t *q) { return q && q->header->magic == 0x43424631 ? queue_state(q->header->state) : SHARED_QUEUE_STATE_INVALID; }
extern "C" void video_queue_write(video_queue_t *q, uint8_t **data, uint32_t *stride, uint64_t ts) {
    DWORD lock=WaitForSingleObject(q->mutex, 0); if (lock != WAIT_OBJECT_0 && lock != WAIT_ABANDONED) { return; }
    auto *dst=reinterpret_cast<uint8_t *>(q->header+1); auto &h=*q->header;
    for (uint32_t y=0;y<h.height;y++) { memcpy(dst+size_t(y)*h.width,data[0]+size_t(y)*stride[0],h.width); }
    dst+=size_t(h.width)*h.height;
    for (uint32_t y=0;y<h.height/2;y++) { memcpy(dst+size_t(y)*h.width,data[1]+size_t(y)*stride[1],h.width); }
    h.timestamp=ts; h.sequence++; h.state=SHARED_QUEUE_STATE_READY; ReleaseMutex(q->mutex);
}
extern "C" bool video_queue_read(video_queue_t *q, nv12_scale_t *scale, void *dst, uint64_t *ts) {
    DWORD lock=WaitForSingleObject(q->mutex, 5); if (lock != WAIT_OBJECT_0 && lock != WAIT_ABANDONED) { return false; }
    auto h=*q->header;
    bool valid=h.magic==0x43424631 && h.state==SHARED_QUEUE_STATE_READY && h.width<=4096 && h.height<=4096 && h.width==uint32_t(scale->src_cx) && h.height==uint32_t(scale->src_cy) && h.width && h.height;
    if (valid) { q->copy.resize(size_t(h.width)*h.height*3/2); memcpy(q->copy.data(),q->header+1,q->copy.size()); *ts=h.timestamp; }
    ReleaseMutex(q->mutex);
    if (valid) {
        const int dw=scale->dst_cx,dh=scale->dst_cy,sw=scale->src_cx,sh=scale->src_cy;
        if(dw<2||dh<2||dw>4096||dh>4096||(dw|dh)&1){return false;}
        if(int64_t(dw)*sh==int64_t(dh)*sw){nv12_do_scale(scale,static_cast<uint8_t *>(dst),q->copy.data());return true;}
        // Preserve the phone's aspect ratio when a meeting app requests 16:9 or 4:3.
        double factor=std::min(double(dw)/sw,double(dh)/sh);
        int fw=std::max(2,int(sw*factor)&~1),fh=std::max(2,int(sh*factor)&~1);
        int left=((dw-fw)/2)&~1,top=((dh-fh)/2)&~1;
        size_t multiplier=scale->format==TARGET_FORMAT_YUY2?2:1;
        q->fitted.resize(size_t(fw)*fh*(multiplier==2?2:3)/ (multiplier==2?1:2));
        nv12_scale_t fit=*scale;fit.dst_cx=fw;fit.dst_cy=fh;nv12_do_scale(&fit,q->fitted.data(),q->copy.data());
        auto *out=static_cast<uint8_t *>(dst);const auto *in=q->fitted.data();
        if(scale->format==TARGET_FORMAT_YUY2){
            for(size_t i=0;i<size_t(dw)*dh*2;i+=2){out[i]=16;out[i+1]=128;}
            for(int y=0;y<fh;y++){memcpy(out+(size_t(top+y)*dw+left)*2,in+size_t(y)*fw*2,fw*2);}
        }else{
            memset(out,16,size_t(dw)*dh);memset(out+size_t(dw)*dh,128,size_t(dw)*dh/2);
            for(int y=0;y<fh;y++){memcpy(out+size_t(top+y)*dw+left,in+size_t(y)*fw,fw);}
            if(scale->format==TARGET_FORMAT_NV12){for(int y=0;y<fh/2;y++){memcpy(out+size_t(dw)*dh+size_t(top/2+y)*dw+left,in+size_t(fw)*fh+size_t(y)*fw,fw);}}
            else{for(int plane=0;plane<2;plane++){for(int y=0;y<fh/2;y++){memcpy(out+size_t(dw)*dh+size_t(plane)*dw*dh/4+size_t(top/2+y)*(dw/2)+left/2,in+size_t(fw)*fh+size_t(plane)*fw*fh/4+size_t(y)*(fw/2),fw/2);}}}
        }
    }
    return valid;
}
