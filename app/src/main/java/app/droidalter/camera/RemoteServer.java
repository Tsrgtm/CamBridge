// SPDX-License-Identifier: GPL-2.0-or-later
package app.droidalter.camera;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Small bounded JSON control endpoint; localhost over USB or authenticated TLS over LAN. */
final class RemoteServer implements AutoCloseable {
    interface Handler { String request(String method,String path,String body) throws Exception; }
    private final ServerSocket server;
    private final String token;
    private final Handler handler;
    private volatile boolean running=true;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    RemoteServer(ServerSocket server,String token,Handler handler) {
        this.server=server;this.token=token;this.handler=handler;
        new Thread(()->{while(running){try{Socket socket=server.accept();socket.setSoTimeout(2500);try{workers.execute(()->serve(socket));}catch(RejectedExecutionException busy){socket.close();}}catch(IOException ignored){}}},"cambridge-controls").start();
    }
    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=input.read())!=-1){if(b=='\n'){return bytes.toString("UTF-8").replace("\r","");}if(bytes.size()>=4096){throw new IOException("Header too long");}bytes.write(b);}
        throw new EOFException();
    }
    private void serve(Socket socket) {
        try(Socket client=socket){
            InputStream input=client.getInputStream();String[] request=line(input).split(" ");
            if(request.length!=3){return;}int length=0;String authorization="",host="",origin="",contentType="";
            for(int count=0;count<32;count++) {String header=line(input);if(header.isEmpty()){break;}int colon=header.indexOf(':');if(colon<0){return;}String key=header.substring(0,colon).trim();String value=header.substring(colon+1).trim();if(key.equalsIgnoreCase("Content-Length")){length=Integer.parseInt(value);}if(key.equalsIgnoreCase("Authorization")){authorization=value;}if(key.equalsIgnoreCase("Host")){host=value;}if(key.equalsIgnoreCase("Origin")||key.equalsIgnoreCase("Sec-Fetch-Site")){origin=value;}if(key.equalsIgnoreCase("Content-Type")){contentType=value;}if(count==31){return;}}
            if(length<0||length>8192){return;}
            String expected=client.getLocalAddress().getHostAddress()+":"+client.getLocalPort();
            boolean localHost=client.getLocalAddress().isLoopbackAddress()&&host.equalsIgnoreCase("localhost:"+client.getLocalPort());
            if(!host.equalsIgnoreCase(expected)&&!localHost){respond(client,400,"{}");return;}
            if(!origin.isEmpty()){respond(client,403,"{\"error\":\"Browser control requests are not accepted\"}");return;}
            if(request[0].equals("POST")&&!contentType.toLowerCase(java.util.Locale.US).startsWith("application/json")){respond(client,415,"{}");return;}
            if(token!=null&&!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8))){respond(client,401,"{\"error\":\"Pairing key did not match\"}");return;}
            byte[] body=new byte[length];new DataInputStream(input).readFully(body);
            if(!request[1].equals("/state")&&!request[1].equals("/control")){respond(client,404,"{}");return;}
            if(!request[0].equals("GET")&&!request[0].equals("POST")){respond(client,405,"{}");return;}
            try{respond(client,200,handler.request(request[0],request[1],new String(body,StandardCharsets.UTF_8)));}
            catch(Exception error){respond(client,400,"{\"error\":\"Unsupported camera setting; refresh capabilities and try again\"}");}
        }catch(Exception ignored){}
    }
    private static void respond(Socket client,int code,String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);OutputStream out=client.getOutputStream();
        out.write(("HTTP/1.1 "+code+" Response\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);
    }
    @Override public void close(){running=false;try{server.close();}catch(IOException ignored){}workers.shutdownNow();}
}
