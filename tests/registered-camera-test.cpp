// SPDX-License-Identifier: GPL-2.0-or-later
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <dshow.h>
#include <cstdio>
int main(){
    CoInitializeEx(nullptr,COINIT_MULTITHREADED);ICreateDevEnum *devices=nullptr;
    HRESULT hr=CoCreateInstance(CLSID_SystemDeviceEnum,nullptr,CLSCTX_INPROC_SERVER,IID_ICreateDevEnum,reinterpret_cast<void **>(&devices));if(FAILED(hr)){return 1;}
    IEnumMoniker *list=nullptr;hr=devices->CreateClassEnumerator(CLSID_VideoInputDeviceCategory,&list,0);if(hr!=S_OK){std::puts("FAIL: no registered camera devices");return 1;}
    bool found=false;IMoniker *item=nullptr;
    while(list->Next(1,&item,nullptr)==S_OK){
        IPropertyBag *bag=nullptr;if(SUCCEEDED(item->BindToStorage(nullptr,nullptr,IID_IPropertyBag,reinterpret_cast<void **>(&bag)))){
            VARIANT name;VariantInit(&name);if(SUCCEEDED(bag->Read(L"FriendlyName",&name,nullptr))&&name.vt==VT_BSTR&&wcscmp(name.bstrVal,L"CamBridge Camera")==0){
                found=true;IBaseFilter *source=nullptr;hr=item->BindToObject(nullptr,nullptr,IID_IBaseFilter,reinterpret_cast<void **>(&source));
                if(FAILED(hr)){std::printf("FAIL: registered source bind %08x\n",unsigned(hr));return 1;}
                IEnumPins *pins=nullptr;IPin *pin=nullptr;source->EnumPins(&pins);pins->Next(1,&pin,nullptr);IAMStreamConfig *config=nullptr;pin->QueryInterface(IID_IAMStreamConfig,reinterpret_cast<void **>(&config));int count=0,size=0;config->GetNumberOfCapabilities(&count,&size);std::printf("PASS: CamBridge Camera enumerates and binds as a normal Windows camera; formats=%d\n",count);
                config->Release();pin->Release();pins->Release();source->Release();
            }VariantClear(&name);bag->Release();
        }item->Release();
    }list->Release();devices->Release();CoUninitialize();if(!found){std::puts("FAIL: CamBridge Camera not visible in Windows enumeration");}return found?0:1;
}
