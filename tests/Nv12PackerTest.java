package app.droidalter.camera;
import java.nio.ByteBuffer;
import java.util.Arrays;
public final class Nv12PackerTest{
    static int assertions;static void check(boolean result,String label){assertions++;if(!result){throw new AssertionError(label);}}
    public static void main(String[] args){
        Nv12Packer packer=new Nv12Packer();byte[] output=new byte[36+12];byte[] y={1,2,3,4,99,99,5,6,7,8};byte[] u={10,20},v={30,40};
        packer.pack(ByteBuffer.wrap(y),6,1,ByteBuffer.wrap(u),2,1,ByteBuffer.wrap(v),2,1,0,0,4,2,output,36);
        check(Arrays.equals(Arrays.copyOfRange(output,36,48),new byte[]{1,2,3,4,5,6,7,8,10,30,20,40}),"Padded luma rows and planar chroma");
        byte[] interleaved={30,10,40,20};ByteBuffer uv=ByteBuffer.wrap(interleaved);uv.position(1);
        packer.pack(ByteBuffer.wrap(y),6,1,uv,4,2,ByteBuffer.wrap(interleaved),4,2,0,0,4,2,output,36);
        check(output[44]==10&&output[45]==30&&output[46]==20&&output[47]==40,"Interleaved NV21 input becomes NV12");
        byte[] padded={0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,2,0,0,0,0,5,6,0,0};byte[] chroma={9,10,11,12};byte[] cropped=new byte[6];
        packer.pack(ByteBuffer.wrap(padded),6,1,ByteBuffer.wrap(chroma),2,1,ByteBuffer.wrap(chroma),2,1,2,2,2,2,cropped,0);
        check(Arrays.equals(cropped,new byte[]{1,2,5,6,12,12}),"Even crop offsets and independent plane positions");
        boolean rejected=false;try{packer.pack(ByteBuffer.wrap(y),6,1,ByteBuffer.wrap(u),2,1,ByteBuffer.wrap(v),2,1,0,0,3,2,output,0);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Odd dimensions rejected");
        System.out.println("PASS: "+assertions+" device-independent YUV packing assertions");
    }
}
