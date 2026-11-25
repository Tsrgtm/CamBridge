// SPDX-License-Identifier: GPL-2.0-or-later
using System;using System.IO;using System.Net;using System.Net.Sockets;using System.Net.Security;using System.Security.Authentication;using System.Security.Cryptography;using System.Security.Cryptography.X509Certificates;using System.Text;using System.Diagnostics;using System.Runtime.InteropServices;
class LiveCameraTest{
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode)]static extern bool SetDllDirectory(string path);
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern int CB_Open();
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern int CB_Hardware();
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern void CB_Close();
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern int CB_Decode(byte[] data,int length,long pts,int angle,int mirror);
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern int CB_Raw(byte[] data,int length,int angle,int mirror);
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern int CB_Preview(byte[] data,int capacity,out int w,out int h,out ulong count,out double ms);
[DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern double CB_PublishMs(); [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)]static extern int CB_SurfaceFrames();
    static byte[] Read(Stream input,int count){byte[] bytes=new byte[count];int offset=0;while(offset<count){int n=input.Read(bytes,offset,count-offset);if(n==0){throw new EndOfStreamException();}offset+=n;}return bytes;}
    static void ReadInto(Stream input,ref byte[] bytes,int length){if(bytes==null||bytes.Length<length){bytes=new byte[length];}int offset=0;while(offset<length){int n=input.Read(bytes,offset,length-offset);if(n==0){throw new EndOfStreamException();}offset+=n;}}
    static uint U32(byte[] b,int o){return(uint)b[o]<<24|(uint)b[o+1]<<16|(uint)b[o+2]<<8|b[o+3];}
    static string Fingerprint(X509Certificate certificate){using(var sha=SHA256.Create()){return BitConverter.ToString(sha.ComputeHash(certificate.GetRawCertData())).Replace("-","");}}
    [MTAThread]static int Main(string[] args){
        if(args.Length<2){Console.Error.WriteLine("Usage: live-camera-test CORE_DIRECTORY usb|CONNECTION_LINK_FILE [frame-count]");return 2;}SetDllDirectory(args[0]);bool wifi=args[1]!="usb";int wanted=args.Length>2?Int32.Parse(args[2]):90;string host="127.0.0.1",key="",pin="";
        if(wifi){var uri=new Uri(File.ReadAllText(args[1]).Trim());host=uri.Host;var query=System.Web.HttpUtility.ParseQueryString(uri.Query);key=query["key"];pin=query["fingerprint"];}
        try{using(var client=new TcpClient()){client.NoDelay=true;client.ReceiveBufferSize=65536;client.ReceiveTimeout=5000;client.SendTimeout=5000;client.Connect(host,wifi?5890:5889);Stream stream=client.GetStream();
            if(wifi){var tls=new SslStream(stream,false,(sender,certificate,chain,errors)=>certificate!=null&&Fingerprint(certificate).Equals(pin,StringComparison.OrdinalIgnoreCase));tls.AuthenticateAsClient("CamBridge Phone",null,SslProtocols.Tls12,false);stream=tls;byte[] secret=Encoding.ASCII.GetBytes(key);stream.Write(new byte[]{0x43,0x42,0x47,0x32,32},0,5);stream.Write(secret,0,secret.Length);}
            stream.Write(new byte[]{0x44,0x41,0x50,0x32},0,4);Read(stream,8);int mode=(int)U32(Read(stream,4),0);int hr=mode==0?CB_Open():0;if(hr<0){Marshal.ThrowExceptionForHR(hr);}stream.Write(new byte[]{0x52,0x44,0x59,0x21},0,4);var watch=Stopwatch.StartNew();int frames=0,angle=90;long totalBytes=0;byte[] data=null;bool raw=false;
            while(frames<wanted){byte[] header=Read(stream,20);if(U32(header,0)!=0x44414c54){throw new Exception("Invalid stream header");}uint size=U32(header,4),flags=U32(header,16);if(size==0||size>32*1024*1024){throw new Exception("Invalid packet bounds");}long pts=unchecked((long)((ulong)U32(header,8)<<32|U32(header,12)));ReadInto(stream,ref data,(int)size);totalBytes+=size;if((flags&2)!=0){angle=(int)pts;}raw=(flags&8)!=0;if(raw){client.ReceiveBufferSize=2*1024*1024;angle=(int)U32(data,8);hr=CB_Raw(data,(int)size,-1,0);}else{hr=CB_Decode(data,(int)size,(flags&2)!=0?0:pts,angle,0);}if(hr<0){Marshal.ThrowExceptionForHR(hr);}if((flags&2)==0){frames++;}if(frames>0&&frames%60==0){Console.WriteLine("Fresh video packets: "+frames);}}
            var pixels=new byte[960*960*4];int w,h;ulong count;double ms;int ready=CB_Preview(pixels,pixels.Length,out w,out h,out count,out ms);if(ready<=0||count<(ulong)(wanted-2)){throw new Exception("Decode backlog or no preview");}
            Console.WriteLine("PASS: "+(wifi?"TLS-pinned paired Wi-Fi":"USB")+" live decode; frames="+count+"; preview="+w+"x"+h+"; observed fps="+(frames/watch.Elapsed.TotalSeconds).ToString("F1")+"; publish="+CB_PublishMs().ToString("F2")+" ms; GPU surfaces="+CB_SurfaceFrames()+"; GPU="+CB_Hardware()+"; MB/s="+(totalBytes/1000000.0/watch.Elapsed.TotalSeconds).ToString("F1")+"; "+(raw?"uncompressed publish=":"last decode+publish=")+ms.ToString("F2")+" ms (not end-to-end latency)");return 0;
        }}catch(Exception error){Console.Error.WriteLine(error.ToString());return 1;}finally{CB_Close();}
    }
}
