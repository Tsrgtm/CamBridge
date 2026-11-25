// SPDX-License-Identifier: GPL-2.0-or-later
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dshow.h>
#include <cstdio>
#include <cstdlib>
#include <vector>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
}
static void check(bool value,const char *message){std::printf("%s: %s\n",value?"PASS":"FAIL",message);if(!value){std::fflush(stdout); ExitProcess(1);}}
int main(int argc,char **argv){
    check(argc==3,"core and camera DLL paths supplied");HMODULE core=LoadLibraryA(argv[1]);check(core!=nullptr,"standalone receiver DLL loads");
    auto open=reinterpret_cast<int(*)()>(GetProcAddress(core,"CB_Open"));auto close=reinterpret_cast<void(*)()>(GetProcAddress(core,"CB_Close"));
    auto decode=reinterpret_cast<int(*)(const uint8_t*,int,long long,int,int)>(GetProcAddress(core,"CB_Decode"));auto preview=reinterpret_cast<int(*)(uint8_t*,int,int*,int*,unsigned long long*,double*)>(GetProcAddress(core,"CB_Preview"));
    check(open&&close&&decode&&preview,"receiver exports present");int hr=open();if(hr<0){std::printf("Open HRESULT: %08x\n",unsigned(hr));}check(hr>=0,"Windows Media Foundation H.264 decoder initializes");
    const AVCodec *codec=avcodec_find_encoder_by_name("libx264");check(codec!=nullptr,"test H.264 encoder available");AVCodecContext *encoder=avcodec_alloc_context3(codec);encoder->width=640;encoder->height=360;encoder->pix_fmt=AV_PIX_FMT_YUV420P;encoder->time_base={1,30};encoder->framerate={30,1};encoder->gop_size=30;encoder->max_b_frames=0;encoder->thread_count=1;
    av_opt_set(encoder->priv_data,"preset","ultrafast",0);av_opt_set(encoder->priv_data,"tune","zerolatency",0);check(avcodec_open2(encoder,codec,nullptr)>=0,"zero-reorder test encoder starts");
    AVFrame *frame=av_frame_alloc();frame->format=encoder->pix_fmt;frame->width=640;frame->height=360;check(av_frame_get_buffer(frame,32)>=0,"test frame allocated");AVPacket *packet=av_packet_alloc();
    std::vector<uint8_t> pixels(960*960*4);int w=0,h=0;unsigned long long count=0;double ms=0;
    for(int i=0;i<12;i++){
        av_frame_make_writable(frame);for(int y=0;y<360;y++){for(int x=0;x<640;x++){frame->data[0][y*frame->linesize[0]+x]=uint8_t(40+(x*150/640)+i);}}for(int y=0;y<180;y++){for(int x=0;x<320;x++){frame->data[1][y*frame->linesize[1]+x]=90;frame->data[2][y*frame->linesize[2]+x]=180;}}
        frame->pts=i;check(avcodec_send_frame(encoder,frame)>=0,"test access unit submitted");
        while(avcodec_receive_packet(encoder,packet)>=0){hr=decode(packet->data,packet->size,i*33333LL,90,0);if(hr<0){std::printf("Decode HRESULT: %08x\n",unsigned(hr));}check(hr>=0,"native H.264 decode and publication");av_packet_unref(packet);}
    }
    check(preview(pixels.data(),int(pixels.size()),&w,&h,&count,&ms)>0,"decoded live preview available");std::printf("Preview dimensions: %dx%d; frames=%llu\n",w,h,count);check(w==360&&h==640,"portrait NV12 rotation and preview dimensions");check(count>=11,"decoder does not accumulate a frame backlog");std::printf("Measured synthetic decode+publish: %.3f ms; frames=%llu\n",ms,count);
    auto raw=reinterpret_cast<int(*)(const uint8_t*,int,int,int)>(GetProcAddress(core,"CB_Raw"));check(raw!=nullptr,"uncompressed receiver export");
    std::vector<uint8_t> nv12(16+3840*2160*3/2,128);
    auto put=[&](int offset,uint32_t value){for(int i=0;i<4;i++){nv12[offset+i]=uint8_t(value>>(24-i*8));}};
    put(0,3840);put(4,2160);put(8,90);put(12,30);
    check(raw(nv12.data(),int(nv12.size())-1,-1,0)<0,"truncated raw frame rejected");
    check(raw(nv12.data(),int(nv12.size()),-1,0)>=0,"4K uncompressed frame publishes without decode");
    check(preview(pixels.data(),int(pixels.size()),&w,&h,&count,&ms)>0&&w==540&&h==960,"4K raw portrait preview");
    HMODULE camera=LoadLibraryA(argv[2]);check(camera!=nullptr,"virtual camera DLL loads without OBS");auto factoryFn=reinterpret_cast<HRESULT(WINAPI*)(REFCLSID,REFIID,void**)>(GetProcAddress(camera,"DllGetClassObject"));
    CLSID cls={0x58ce1ec1,0x0a5a,0x4ea9,{0x93,0x5d,0x6a,0xdb,0x78,0x04,0x3f,0x91}};IClassFactory *factory=nullptr;check(factoryFn&&SUCCEEDED(factoryFn(cls,IID_IClassFactory,reinterpret_cast<void **>(&factory))),"CamBridge COM factory");
    IBaseFilter *filter=nullptr;check(SUCCEEDED(factory->CreateInstance(nullptr,IID_IBaseFilter,reinterpret_cast<void **>(&filter))),"camera source construction");IEnumPins *pins=nullptr;IPin *pin=nullptr;check(SUCCEEDED(filter->EnumPins(&pins))&&pins->Next(1,&pin,nullptr)==S_OK,"camera output pin");
    IAMStreamConfig *config=nullptr;check(SUCCEEDED(pin->QueryInterface(IID_IAMStreamConfig,reinterpret_cast<void **>(&config))),"camera format negotiation");AM_MEDIA_TYPE *type=nullptr;check(SUCCEEDED(config->GetFormat(&type))&&type&&type->cbFormat>=sizeof(VIDEOINFOHEADER),"valid default media format");auto *video=reinterpret_cast<VIDEOINFOHEADER *>(type->pbFormat);check(video->bmiHeader.biWidth>0&&video->bmiHeader.biHeight>0,"nonzero output size");CoTaskMemFree(type->pbFormat);CoTaskMemFree(type);
    IGraphBuilder *graph=nullptr;check(SUCCEEDED(CoCreateInstance(CLSID_FilterGraph,nullptr,CLSCTX_INPROC_SERVER,IID_IGraphBuilder,reinterpret_cast<void **>(&graph))),"independent DirectShow graph");
    IBaseFilter *sink=nullptr;CLSID nullRenderer={0xc1f400a4,0x3f08,0x11d3,{0x9f,0x0b,0x00,0x60,0x08,0x03,0x9e,0x37}};check(SUCCEEDED(CoCreateInstance(nullRenderer,nullptr,CLSCTX_INPROC_SERVER,IID_IBaseFilter,reinterpret_cast<void **>(&sink))),"independent camera consumer");
    graph->AddFilter(filter,L"CamBridge");graph->AddFilter(sink,L"Test consumer");IEnumPins *sinkPins=nullptr;IPin *sinkPin=nullptr;sink->EnumPins(&sinkPins);sinkPins->Next(1,&sinkPin,nullptr);check(SUCCEEDED(graph->ConnectDirect(pin,sinkPin,nullptr)),"consumer accepts CamBridge camera frames");IMediaControl *control=nullptr;graph->QueryInterface(IID_IMediaControl,reinterpret_cast<void **>(&control));check(SUCCEEDED(control->Run()),"camera graph runs independently of OBS");Sleep(300);control->Stop();control->Release();sinkPin->Release();sinkPins->Release();sink->Release();graph->Release();config->Release();pin->Release();pins->Release();filter->Release();factory->Release();
    av_packet_free(&packet);av_frame_free(&frame);avcodec_free_context(&encoder);close();std::puts("All standalone Windows camera checks passed.");return 0;
}
