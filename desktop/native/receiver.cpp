// SPDX-License-Identifier: GPL-2.0-or-later
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <codecapi.h>
#include <d3d11.h>
#include <d3d10.h>
#include <dxgi1_6.h>
#include <vector>
#include <mutex>
#include <algorithm>
#include <cstring>
#include "shared-memory-queue.h"
#include <libyuv/rotate.h>
#include <libyuv/planar_functions.h>

template<class T> struct Ptr {
    T *p=nullptr; ~Ptr(){if(p){p->Release();}} T *operator->() const{return p;}
    T **out(){if(p){p->Release();p=nullptr;}return &p;}
};
static std::mutex gate;
static Ptr<IMFTransform> decoder;
static Ptr<ID3D11Device> gpuDevice;
static Ptr<ID3D11DeviceContext> gpuContext;
static Ptr<IMFDXGIDeviceManager> gpuManager;
static Ptr<ID3D11Texture2D> staging;
static Ptr<IMFSample> workingSample;
static Ptr<IMFMediaBuffer> workingBuffer;
static DWORD workingSize=0;
static bool gpuMode=false,allowGpu=true;
static UINT stagingWidth=0,stagingHeight=0;
static video_queue_t *queue=nullptr;
static UINT32 width=0,height=0,stride=0,queueW=0,queueH=0,codedHeight=0,cropX=0,cropY=0;
static int targetFps=30,queueFps=0;
static int rotation=90; static bool mirror=false,started=false;
static std::vector<uint8_t> latest,chroma;
static uint64_t frames=0; static double decodeMs=0,publishMs=0; static int surfaceFrames=0;
static LARGE_INTEGER frequency;
static HRESULT outputType() {
    for(DWORD i=0;;i++) {
        Ptr<IMFMediaType> type; HRESULT hr=decoder->GetOutputAvailableType(0,i,type.out()); if(FAILED(hr)){return hr;}
        GUID subtype{};type->GetGUID(MF_MT_SUBTYPE,&subtype);
        if(subtype!=MFVideoFormat_NV12){continue;}
        hr=decoder->SetOutputType(0,type.p,0);if(FAILED(hr)){return hr;}
        hr=MFGetAttributeSize(type.p,MF_MT_FRAME_SIZE,&width,&height);if(FAILED(hr)){return hr;}
        stride=MFGetAttributeUINT32(type.p,MF_MT_DEFAULT_STRIDE,width);
        if(!width || !height || width>4096 || height>4096 || (width|height)&1 || stride<width || stride>8192){return E_INVALIDARG;}
        codedHeight=height; cropX=cropY=0;
        MFVideoArea aperture{};
        HRESULT crop=type->GetBlob(MF_MT_MINIMUM_DISPLAY_APERTURE,reinterpret_cast<UINT8 *>(&aperture),sizeof(aperture),nullptr);
        if(FAILED(crop)){crop=type->GetBlob(MF_MT_GEOMETRIC_APERTURE,reinterpret_cast<UINT8 *>(&aperture),sizeof(aperture),nullptr);}
        if(SUCCEEDED(crop)&&aperture.Area.cx>0&&aperture.Area.cy>0&&aperture.OffsetX.value>=0&&aperture.OffsetY.value>=0&&UINT32(aperture.OffsetX.value+aperture.Area.cx)<=width&&UINT32(aperture.OffsetY.value+aperture.Area.cy)<=height){cropX=UINT32(aperture.OffsetX.value)&~1u;cropY=UINT32(aperture.OffsetY.value)&~1u;width=UINT32(aperture.Area.cx)&~1u;height=UINT32(aperture.Area.cy)&~1u;}
        return S_OK;
    }
}
static void publish(const uint8_t *data,size_t length) { LARGE_INTEGER publishStart,publishEnd;QueryPerformanceCounter(&publishStart);
    if(length<size_t(stride)*codedHeight*3/2){return;}
    const UINT32 w=(rotation==90||rotation==270)?height:width,h=(rotation==90||rotation==270)?width:height;
    latest.resize(size_t(w)*h*3/2);
    // Rotate luma and chroma as pixel / UV-pair planes, preserving NV12 layout.
    if(!mirror&&rotation!=0){
        chroma.resize(size_t(w)*h/2);auto *u=chroma.data();auto *v=u+size_t(w)*h/4;
        int result=libyuv::NV12ToI420Rotate(data+size_t(cropY)*stride+cropX,stride,data+size_t(stride)*codedHeight+size_t(cropY/2)*stride+cropX,stride,latest.data(),w,u,w/2,v,w/2,width,height,libyuv::RotationMode(rotation));
        if(result!=0){return;}libyuv::MergeUVPlane(u,w/2,v,w/2,latest.data()+size_t(w)*h,w,w/2,h/2);
    }else{
    const int angle=rotation;
    for(int plane=0;plane<2;plane++) {
        UINT32 sw=plane?width/2:width,sh=plane?height/2:height,dw=plane?w/2:w,unit=plane?2:1;
        const auto *src=data+(plane?size_t(stride)*codedHeight+size_t(cropY/2)*stride+cropX:size_t(cropY)*stride+cropX);
        auto *dst=latest.data()+(plane?size_t(w)*h:0);
        if(angle==0&&!mirror){for(UINT32 y=0;y<sh;y++){memcpy(dst+size_t(y)*w,src+size_t(y)*stride,w);}continue;}
        // Tiled transpose keeps 90-degree writes in cache, including on 4K input.
        for(UINT32 by=0;by<sh;by+=16){for(UINT32 bx=0;bx<sw;bx+=16){
            for(UINT32 y=by;y<std::min(sh,by+16);y++){for(UINT32 x=bx;x<std::min(sw,bx+16);x++){
                UINT32 dx=x,dy=y;
                if(angle==90){dx=sh-1-y;dy=x;}else if(angle==180){dx=sw-1-x;dy=sh-1-y;}else if(angle==270){dx=y;dy=sw-1-x;}
                if(mirror){dx=dw-1-dx;}
                size_t out=(size_t(dy)*dw+dx)*unit,in=size_t(y)*stride+x*unit;
                dst[out]=src[in];if(plane){dst[out+1]=src[in+1];}
            }}
        }}
    }
    }
    if(!queue||w!=queueW||h!=queueH||queueFps!=targetFps){video_queue_close(queue);queue=video_queue_create(w,h,10000000/targetFps);queueW=w;queueH=h;queueFps=targetFps;}
    if(queue){uint8_t *planes[]={latest.data(),latest.data()+size_t(w)*h};uint32_t strides[]={w,w};LARGE_INTEGER now;QueryPerformanceCounter(&now);video_queue_write(queue,planes,strides,uint64_t(now.QuadPart/frequency.QuadPart)*10000000+uint64_t(now.QuadPart%frequency.QuadPart)*10000000/frequency.QuadPart);}
    frames++;QueryPerformanceCounter(&publishEnd);publishMs=double(publishEnd.QuadPart-publishStart.QuadPart)*1000/frequency.QuadPart;
}
static HRESULT drain() {
    for(int attempt=0;attempt<16;attempt++){
        MFT_OUTPUT_STREAM_INFO info{};HRESULT hr=decoder->GetOutputStreamInfo(0,&info);if(FAILED(hr)){return hr;}
        IMFSample *requested=nullptr;
        if(!(info.dwFlags&(MFT_OUTPUT_STREAM_PROVIDES_SAMPLES|MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES))){
            DWORD needed=std::max<DWORD>(info.cbSize,stride*std::max<UINT32>(height,1080)*3/2);
            if(!workingSample.p||workingSize<needed){
                workingSample.out();workingBuffer.out();workingSize=0;
                hr=MFCreateSample(workingSample.out());if(FAILED(hr)){return hr;}
                hr=MFCreateMemoryBuffer(needed,workingBuffer.out());if(FAILED(hr)){return hr;}
                workingSample->AddBuffer(workingBuffer.p);workingSize=needed;
            }
            workingBuffer->SetCurrentLength(0);requested=workingSample.p;
        }
        MFT_OUTPUT_DATA_BUFFER out{};out.pSample=requested;DWORD status=0;
        hr=decoder->ProcessOutput(0,1,&out,&status);if(out.pEvents){out.pEvents->Release();}
        if(hr==MF_E_TRANSFORM_STREAM_CHANGE){hr=outputType();if(FAILED(hr)){return hr;}continue;}
        if(hr==MF_E_TRANSFORM_NEED_MORE_INPUT){return S_OK;}if(FAILED(hr)){return hr;}
        Ptr<IMFSample> provided;if(out.pSample!=requested){provided.p=out.pSample;}if(!out.pSample){continue;}
        Ptr<IMFMediaBuffer> buffer;hr=out.pSample->GetBufferByIndex(0,buffer.out());if(FAILED(hr)){return hr;}
        Ptr<IMFDXGIBuffer> surface;
        if(gpuMode&&SUCCEEDED(buffer->QueryInterface(__uuidof(IMFDXGIBuffer),reinterpret_cast<void **>(surface.out())))){
            Ptr<ID3D11Texture2D> texture;UINT index=0;hr=surface->GetResource(__uuidof(ID3D11Texture2D),reinterpret_cast<void **>(texture.out()));if(FAILED(hr)){return hr;}surface->GetSubresourceIndex(&index);
            surfaceFrames++; D3D11_TEXTURE2D_DESC description{};texture->GetDesc(&description);if(description.Format!=DXGI_FORMAT_NV12){return MF_E_INVALIDMEDIATYPE;}
            if(!staging.p||stagingWidth!=description.Width||stagingHeight!=description.Height){
                staging.out();stagingWidth=description.Width;stagingHeight=description.Height;description.ArraySize=1;description.MipLevels=1;description.Usage=D3D11_USAGE_STAGING;description.BindFlags=0;description.CPUAccessFlags=D3D11_CPU_ACCESS_READ;description.MiscFlags=0;
                hr=gpuDevice->CreateTexture2D(&description,nullptr,staging.out());if(FAILED(hr)){return hr;}
            }
            gpuContext->CopySubresourceRegion(staging.p,0,0,0,0,texture.p,index,nullptr);D3D11_MAPPED_SUBRESOURCE mapped{};hr=gpuContext->Map(staging.p,0,D3D11_MAP_READ,0,&mapped);if(FAILED(hr)){return hr;}
            stride=mapped.RowPitch;codedHeight=stagingHeight;publish(static_cast<const uint8_t *>(mapped.pData),size_t(stride)*codedHeight*3/2);gpuContext->Unmap(staging.p,0);
        }else{
            Ptr<IMFMediaBuffer> contiguous;hr=out.pSample->ConvertToContiguousBuffer(contiguous.out());if(FAILED(hr)){return hr;}
            BYTE *bytes=nullptr;DWORD length=0;hr=contiguous->Lock(&bytes,nullptr,&length);if(FAILED(hr)){return hr;}publish(bytes,length);contiguous->Unlock();
        }
    }return S_OK;
}
#define API extern "C" __declspec(dllexport)
static uint32_t raw32(const uint8_t *p){return uint32_t(p[0])<<24|uint32_t(p[1])<<16|uint32_t(p[2])<<8|p[3];}
API int CB_Raw(const uint8_t *bytes,int length,int angle,int mirrored){
    std::lock_guard<std::mutex> lock(gate);
    if(!bytes||length<16){return E_INVALIDARG;}
    uint32_t w=raw32(bytes),h=raw32(bytes+4),fps=raw32(bytes+12);
    if(w<2||h<2||w>4096||h>4096||((w|h)&1)||uint64_t(w)*h*3/2+16!=uint64_t(length)){return E_INVALIDARG;}
    LARGE_INTEGER begin,end;if(!frequency.QuadPart){QueryPerformanceFrequency(&frequency);}QueryPerformanceCounter(&begin);
    width=stride=w;height=codedHeight=h;cropX=cropY=0;rotation=angle<0?int(raw32(bytes+8)):angle;mirror=mirrored!=0;targetFps=std::max(1,std::min(int(fps),60));
    if(rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270){return E_INVALIDARG;}
    publish(bytes+16,length-16);QueryPerformanceCounter(&end);decodeMs=double(end.QuadPart-begin.QuadPart)*1000/frequency.QuadPart;return S_OK;
}
API double CB_PublishMs(){std::lock_guard<std::mutex> lock(gate);return publishMs;} API int CB_SurfaceFrames(){std::lock_guard<std::mutex> lock(gate);return surfaceFrames;}
API int CB_Hardware(){std::lock_guard<std::mutex> lock(gate);return gpuMode?1:0;}
API void CB_Rate(int fps) { std::lock_guard<std::mutex> lock(gate);targetFps=std::max(1,std::min(fps,60)); }
API int CB_Open() {
    std::lock_guard<std::mutex> lock(gate);
    if(started){return S_OK;}
    HRESULT hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED);if(FAILED(hr)){return hr;}
    hr=MFStartup(MF_VERSION);if(FAILED(hr)){return hr;}started=true;QueryPerformanceFrequency(&frequency);
    hr=CoCreateInstance(CLSID_CMSH264DecoderMFT,nullptr,CLSCTX_INPROC_SERVER,IID_IMFTransform,reinterpret_cast<void **>(decoder.out()));if(FAILED(hr)){return hr;}
    if(allowGpu){
        D3D_FEATURE_LEVEL level;
        Ptr<IDXGIFactory6> adapters;Ptr<IDXGIAdapter1> preferred;
        if(SUCCEEDED(CreateDXGIFactory1(__uuidof(IDXGIFactory6),reinterpret_cast<void **>(adapters.out())))){
            adapters->EnumAdapterByGpuPreference(0,DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,__uuidof(IDXGIAdapter1),reinterpret_cast<void **>(preferred.out()));
        }
        HRESULT device=D3D11CreateDevice(preferred.p,preferred.p?D3D_DRIVER_TYPE_UNKNOWN:D3D_DRIVER_TYPE_HARDWARE,nullptr,D3D11_CREATE_DEVICE_VIDEO_SUPPORT|D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,gpuDevice.out(),&level,gpuContext.out());
        if(FAILED(device)&&preferred.p){device=D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,D3D11_CREATE_DEVICE_VIDEO_SUPPORT|D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,gpuDevice.out(),&level,gpuContext.out());}
        if(SUCCEEDED(device)){
            Ptr<ID3D10Multithread> protection;if(SUCCEEDED(gpuContext->QueryInterface(__uuidof(ID3D10Multithread),reinterpret_cast<void **>(protection.out())))){protection->SetMultithreadProtected(TRUE);}
            UINT token=0;if(SUCCEEDED(MFCreateDXGIDeviceManager(&token,gpuManager.out()))&&SUCCEEDED(gpuManager->ResetDevice(gpuDevice.p,token))&&SUCCEEDED(decoder->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER,reinterpret_cast<ULONG_PTR>(gpuManager.p)))){gpuMode=true;}
        }
    }
    Ptr<ICodecAPI> codec;
    if(SUCCEEDED(decoder->QueryInterface(IID_ICodecAPI,reinterpret_cast<void **>(codec.out())))){
        VARIANT value;VariantInit(&value);value.vt=VT_UI4;value.ulVal=1;codec->SetValue(&CODECAPI_AVLowLatencyMode,&value);
        value.ulVal=4;codec->SetValue(&CODECAPI_AVDecNumWorkerThreads,&value);
    }

    Ptr<IMFMediaType> input;hr=MFCreateMediaType(input.out());if(FAILED(hr)){return hr;}
    input->SetGUID(MF_MT_MAJOR_TYPE,MFMediaType_Video);input->SetGUID(MF_MT_SUBTYPE,MFVideoFormat_H264);
    hr=decoder->SetInputType(0,input.p,0);if(FAILED(hr)){if(gpuMode){allowGpu=false;}return hr;}
    hr=outputType();if(FAILED(hr)){return hr;}
    decoder->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING,0);decoder->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM,0);
    frames=0;return S_OK;
}
API void CB_Close() {
    std::lock_guard<std::mutex> lock(gate);decoder.out();workingSample.out();workingBuffer.out();workingSize=0;staging.out();gpuManager.out();gpuContext.out();gpuDevice.out();gpuMode=false;stagingWidth=stagingHeight=0;video_queue_close(queue);queue=nullptr;latest.clear();frames=0;width=height=stride=queueW=queueH=0;
    if(started){MFShutdown();CoUninitialize();started=false;}
}
API int CB_Decode(const uint8_t *bytes,int length,long long pts,int angle,int mirrored) {
    std::lock_guard<std::mutex> lock(gate);if(!decoder.p||!bytes||length<1||length>8*1024*1024){return E_INVALIDARG;}
    LARGE_INTEGER begin,end;QueryPerformanceCounter(&begin);rotation=angle;mirror=mirrored!=0;
    if(rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270){rotation=90;}
    Ptr<IMFSample> sample;Ptr<IMFMediaBuffer> buffer;HRESULT hr=MFCreateSample(sample.out());if(FAILED(hr)){return hr;}
    hr=MFCreateMemoryBuffer(length,buffer.out());if(FAILED(hr)){return hr;}
    BYTE *target=nullptr;hr=buffer->Lock(&target,nullptr,nullptr);if(FAILED(hr)){return hr;}memcpy(target,bytes,length);buffer->Unlock();buffer->SetCurrentLength(length);sample->AddBuffer(buffer.p);sample->SetSampleTime(pts*10);sample->SetSampleDuration(10000000/targetFps);
    hr=decoder->ProcessInput(0,sample.p,0);
    if(hr==MF_E_NOTACCEPTING){HRESULT pending=drain();if(FAILED(pending)){return pending;}hr=decoder->ProcessInput(0,sample.p,0);}
    if(SUCCEEDED(hr)){hr=drain();}if(FAILED(hr)&&gpuMode){allowGpu=false;}QueryPerformanceCounter(&end);decodeMs=double(end.QuadPart-begin.QuadPart)*1000/frequency.QuadPart;return hr;
}
API int CB_Preview(uint8_t *dst,int capacity,int *w,int *h,unsigned long long *count,double *milliseconds) {
    std::lock_guard<std::mutex> lock(gate);*count=frames;*milliseconds=decodeMs;if(latest.empty()||!queueW||!queueH){return 0;}
    double scale=std::min(1.0,960.0/std::max(queueW,queueH));*w=int(queueW*scale);*h=int(queueH*scale);if(capacity<*w**h*4){return -1;}
    auto clamp=[](int v){return uint8_t(std::max(0,std::min(v,255)));};
    for(int y=0;y<*h;y++){for(int x=0;x<*w;x++){
        int sx=int(x/scale),sy=int(y/scale);int Y=latest[size_t(sy)*queueW+sx]-16;size_t uv=size_t(queueW)*queueH+size_t(sy/2)*queueW+(sx&~1);int U=latest[uv]-128,V=latest[uv+1]-128;size_t p=(size_t(y)**w+x)*4;
        dst[p]=clamp((298*Y+541*U+128)>>8);dst[p+1]=clamp((298*Y-55*U-136*V+128)>>8);dst[p+2]=clamp((298*Y+459*V+128)>>8);dst[p+3]=255;
    }}return 1;
}
