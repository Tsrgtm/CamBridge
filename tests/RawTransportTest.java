package app.droidalter.camera;
import java.io.*;import java.net.*;import java.nio.*;import java.util.concurrent.*;
public final class RawTransportTest{
    static void check(boolean value,String name){if(!value){throw new AssertionError(name);}}
    public static void main(String[] args)throws Exception{
        CountDownLatch connected=new CountDownLatch(1),released=new CountDownLatch(1);
        try(UsbStreamServer server=new UsbStreamServer(0,new UsbStreamServer.Listener(){public void onConnection(boolean value){if(value){connected.countDown();}}public void requestKeyFrame(){}})){
            server.setRawMode(true);server.setRotation(90);
            try(Socket client=new Socket("127.0.0.1",server.port())){
                client.setSoTimeout(3000);DataOutputStream out=new DataOutputStream(client.getOutputStream());DataInputStream in=new DataInputStream(client.getInputStream());out.writeInt(0x44415032);check(in.readLong()>0,"Receiver clock reply");check(in.readInt()==1,"Raw mode advertised before sending frames");check(connected.getCount()==1,"No stream starts before receiver is ready");out.writeInt(0x52445921);check(connected.await(2,TimeUnit.SECONDS),"Ready handshake completes");
                byte[] data=new byte[48];for(int i=36;i<48;i++){data[i]=(byte)i;}check(server.publishRaw(data,4,2,30,1234,released::countDown),"Raw packet accepted");byte[] wire=new byte[48];in.readFully(wire);ByteBuffer header=ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);check(header.getInt()==0x44414c54&&header.getInt()==28&&header.getLong()==1234&&header.getInt()==8,"Raw wire header");check(header.getInt()==4&&header.getInt()==2&&header.getInt()==90&&header.getInt()==30,"Raw dimensions orientation and FPS");check(released.await(2,TimeUnit.SECONDS),"Reusable buffer released after transmission");
            }
        }
        System.out.println("PASS: raw USB framing, ready handshake, and buffer ownership");
    }
}
