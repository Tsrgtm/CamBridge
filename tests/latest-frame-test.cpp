// SPDX-License-Identifier: GPL-2.0-or-later
#include <cstdio>
#include <cstdlib>
#include <vector>
#include <cstring>
#include "shared-memory-queue.h"
#include "tiny-nv12-scale.h"
static void check(bool result,const char *message){std::printf("%s: %s\n",result?"PASS":"FAIL",message);if(!result){std::exit(1);}}
int main(){
    auto *writer=video_queue_create(16,8,333333);check(writer!=nullptr,"one camera producer opens");
    check(video_queue_create(16,8,333333)==nullptr,"duplicate producer rejected");
    auto *reader=video_queue_open();check(reader!=nullptr,"independent consumer opens");
    std::vector<uint8_t> source(16*8*3/2,128);uint8_t *planes[]={source.data(),source.data()+128};uint32_t strides[]={16,16};
    for(int value:{70,120,210}){memset(source.data(),value,128);video_queue_write(writer,planes,strides,value);}
    nv12_scale_t scale;nv12_scale_init(&scale,TARGET_FORMAT_NV12,16,16,16,8);std::vector<uint8_t> out(16*16*2);uint64_t timestamp=0;
    check(video_queue_read(reader,&scale,out.data(),&timestamp),"latest frame is readable");
    check(timestamp==210&&out[4*16]==210,"reader receives the newest frame, without replaying older frames");
    check(out[0]==16&&out[15*16]==16,"portrait/landscape aspect preserved with black bars");
    scale.format=TARGET_FORMAT_I420;check(video_queue_read(reader,&scale,out.data(),&timestamp)&&out[0]==16&&out[4*16]==210,"I420 output letterboxes correctly");
    scale.format=TARGET_FORMAT_YUY2;check(video_queue_read(reader,&scale,out.data(),&timestamp)&&out[0]==16&&out[4*16*2]==210,"YUY2 output letterboxes correctly");
    video_queue_close(writer);check(video_queue_state(reader)==SHARED_QUEUE_STATE_STOPPING,"producer shutdown is visible to consumers");
    writer=video_queue_create(8,16,166666);check(writer!=nullptr,"producer can reopen at a new resolution");
    uint32_t width=0,height=0;uint64_t interval=0;video_queue_get_info(reader,&width,&height,&interval);check(width==8&&height==16&&interval==166666,"existing consumer sees new dimensions and frame rate");
    video_queue_close(writer);video_queue_close(reader);std::puts("All latest-frame camera checks passed.");
}
