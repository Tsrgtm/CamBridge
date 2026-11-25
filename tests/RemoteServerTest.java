package app.droidalter.camera;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class RemoteServerTest {
    static int assertions;
    static void check(boolean value,String name){assertions++;if(!value){throw new AssertionError(name);}}
    static String request(int port,String data)throws Exception{
        if(!data.contains("Host:")){data=data.replaceFirst("\\r\\n","\r\nHost: 127.0.0.1:"+port+"\r\n");}
        if(data.startsWith("POST")&&!data.contains("Content-Type:")){data=data.replaceFirst("\\r\\n","\r\nContent-Type: application/json\r\n");}
        try(Socket client=new Socket("127.0.0.1",port)){client.setSoTimeout(3000);client.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));return new String(client.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
    }
    public static void main(String[] args)throws Exception{
        AtomicInteger called=new AtomicInteger();ServerSocket socket=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));
        try(RemoteServer server=new RemoteServer(socket,"test-secret",(method,path,body)->{called.incrementAndGet();return "{\"ok\":true}";})){
            int port=socket.getLocalPort();
            check(request(port,"GET /state HTTP/1.1\r\n\r\n").contains("401"),"Unauthenticated control rejected");
            check(called.get()==0,"Authentication precedes camera handler");
            check(request(port,"GET /state HTTP/1.1\r\nAuthorization: Bearer test-secret\r\n\r\n").contains("200"),"Paired state request");
            check(request(port,"POST /control HTTP/1.1\r\nAuthorization: Bearer test-secret\r\nContent-Length: 2\r\n\r\n{}").contains("200"),"Paired camera update");
            check(called.get()==2,"Only authorized requests reached handler");
            check(request(port,"GET /state HTTP/1.1\r\nHost: attacker.example\r\nAuthorization: Bearer test-secret\r\n\r\n").contains("400"),"DNS rebinding host rejected");
            check(request(port,"POST /control HTTP/1.1\r\nOrigin: https://attacker.example\r\nAuthorization: Bearer test-secret\r\nContent-Length: 2\r\n\r\n{}").contains("403"),"Cross-site browser commands rejected");
            check(request(port,"POST /control HTTP/1.1\r\nContent-Type: text/plain\r\nAuthorization: Bearer test-secret\r\nContent-Length: 2\r\n\r\n{}").contains("415"),"Non-JSON browser-safe content type rejected");
            check(request(port,"GET /other HTTP/1.1\r\nAuthorization: Bearer test-secret\r\n\r\n").contains("404"),"Unknown endpoint rejected");
            check(request(port,"POST /control HTTP/1.1\r\nAuthorization: Bearer test-secret\r\nContent-Length: 999999\r\n\r\n").isEmpty(),"Oversized command rejected before allocation");
        }
        System.out.println("PASS: "+assertions+" remote control authentication and bounds assertions");
        ServerSocket videoSocket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));String token="0123456789abcdef0123456789abcdef";
        try(UsbStreamServer video=new UsbStreamServer(videoSocket,token,new UsbStreamServer.Listener(){public void onConnection(boolean value){}public void requestKeyFrame(){}})){
            try(Socket client=new Socket("127.0.0.1",video.port())){client.setSoTimeout(3000);new DataOutputStream(client.getOutputStream()).writeInt(0x44415047);check(client.getInputStream().read()==-1,"Legacy handshake rejected on paired transport");}
            try(Socket client=new Socket("127.0.0.1",video.port())){client.setSoTimeout(3000);DataOutputStream output=new DataOutputStream(client.getOutputStream());output.writeInt(0x43424732);output.writeByte(32);output.write(token.getBytes(StandardCharsets.US_ASCII));output.writeInt(0x44415047);check(new DataInputStream(client.getInputStream()).readLong()>0,"Paired video clock handshake");}
        }
        System.out.println("PASS: paired video authentication");
    }
}
