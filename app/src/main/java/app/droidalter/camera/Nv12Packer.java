// SPDX-License-Identifier: GPL-2.0-or-later
package app.droidalter.camera;
import java.nio.ByteBuffer;

/** Packs arbitrary Camera2 YUV planes without assuming an OEM's strides or UV order. */
final class Nv12Packer {
    private byte[] row=new byte[0],uRow=new byte[0],vRow=new byte[0];
    void pack(ByteBuffer y,int yStride,int yPixel,ByteBuffer u,int uStride,int uPixel,ByteBuffer v,int vStride,int vPixel,int left,int top,int width,int height,byte[] out,int offset){
        if(width<2||height<2||((width|height|left|top)&1)!=0||left<0||top<0||(long)offset+(long)width*height*3/2>out.length){throw new IllegalArgumentException("Invalid YUV crop or frame size");}
        if(yPixel<1||uPixel<1||vPixel<1){throw new IllegalArgumentException("Invalid plane pixel stride");}
        ByteBuffer yy=y.duplicate(),uu=u.duplicate(),vv=v.duplicate();int yBase=y.position(),uBase=u.position(),vBase=v.position();
        int ySpan=(width-1)*yPixel+1,uvWidth=width/2,uSpan=(uvWidth-1)*uPixel+1,vSpan=(uvWidth-1)*vPixel+1;
        if(row.length<ySpan){row=new byte[ySpan];}if(uRow.length<uSpan){uRow=new byte[uSpan];}if(vRow.length<vSpan){vRow=new byte[vSpan];}
        for(int line=0;line<height;line++){
            yy.position(yBase+(top+line)*yStride+left*yPixel);
            if(yPixel==1){yy.get(out,offset+line*width,width);}else{yy.get(row,0,ySpan);for(int x=0;x<width;x++){out[offset+line*width+x]=row[x*yPixel];}}
        }
        int chroma=offset+width*height;
        for(int line=0;line<height/2;line++){
            uu.position(uBase+(top/2+line)*uStride+left/2*uPixel);vv.position(vBase+(top/2+line)*vStride+left/2*vPixel);
            uu.get(uRow,0,uSpan);vv.get(vRow,0,vSpan);
            for(int x=0;x<uvWidth;x++){out[chroma+line*width+x*2]=uRow[x*uPixel];out[chroma+line*width+x*2+1]=vRow[x*vPixel];}
        }
    }
}
