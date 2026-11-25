// SPDX-License-Identifier: GPL-2.0-or-later
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vector>
#include <mutex>
static std::vector<uint8_t> image;
static std::once_flag initialized;
bool initialize_placeholder(){
    std::call_once(initialized,[]{
        const int w=1280,h=720;image.assign(w*h*3/2,128);
        BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=w;info.bmiHeader.biHeight=-h;info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;
        void *pixels=nullptr;HDC dc=CreateCompatibleDC(nullptr);HBITMAP bitmap=CreateDIBSection(dc,&info,DIB_RGB_COLORS,&pixels,nullptr,0);auto old=SelectObject(dc,bitmap);
        RECT area{0,0,w,h};HBRUSH background=CreateSolidBrush(RGB(20,35,51));FillRect(dc,&area,background);DeleteObject(background);SetBkMode(dc,TRANSPARENT);SetTextColor(dc,RGB(239,243,246));
        HFONT title=CreateFontW(78,0,0,0,FW_BOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,ANTIALIASED_QUALITY,DEFAULT_PITCH,L"Segoe UI");auto font=SelectObject(dc,title);RECT label{0,240,w,360};DrawTextW(dc,L"CamBridge",-1,&label,DT_CENTER|DT_SINGLELINE);
        HFONT text=CreateFontW(30,0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,ANTIALIASED_QUALITY,DEFAULT_PITCH,L"Segoe UI");SelectObject(dc,text);SetTextColor(dc,RGB(130,200,255));label={0,370,w,430};DrawTextW(dc,L"Open CamBridge on your PC and connect your phone",-1,&label,DT_CENTER|DT_SINGLELINE);GdiFlush();
        auto *b=static_cast<uint8_t *>(pixels);
        for(int y=0;y<h;y++){for(int x=0;x<w;x++){size_t p=(size_t(y)*w+x)*4;int B=b[p],G=b[p+1],R=b[p+2];image[size_t(y)*w+x]=uint8_t(((66*R+129*G+25*B+128)>>8)+16);}}
        for(int y=0;y<h;y+=2){for(int x=0;x<w;x+=2){int U=0,V=0;for(int j=0;j<2;j++){for(int i=0;i<2;i++){size_t p=(size_t(y+j)*w+x+i)*4;int B=b[p],G=b[p+1],R=b[p+2];U+=((-38*R-74*G+112*B+128)>>8)+128;V+=((112*R-94*G-18*B+128)>>8)+128;}}size_t p=w*h+size_t(y/2)*w+x;image[p]=uint8_t(U/4);image[p+1]=uint8_t(V/4);}}
        SelectObject(dc,font);DeleteObject(title);DeleteObject(text);SelectObject(dc,old);DeleteObject(bitmap);DeleteDC(dc);
    });return !image.empty();
}
const uint8_t *get_placeholder_ptr(){return image.data();}
const bool get_placeholder_size(int *w,int *h){*w=1280;*h=720;return true;}
