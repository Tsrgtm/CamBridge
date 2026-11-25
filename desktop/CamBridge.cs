// SPDX-License-Identifier: GPL-2.0-or-later
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

internal static class Native {
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)] internal static extern int CB_Open();
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)] internal static extern int CB_Hardware();
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)] internal static extern void CB_Rate(int fps);
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)] internal static extern void CB_Close();
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)] internal static extern int CB_Decode(byte[] bytes,int length,long pts,int angle,int mirror);
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)] internal static extern int CB_Raw(byte[] bytes,int length,int angle,int mirror);
    [DllImport("CamBridgeCore.dll",CallingConvention=CallingConvention.Cdecl)] internal static extern int CB_Preview(byte[] target,int capacity,out int width,out int height,out ulong count,out double milliseconds);
}
internal sealed class Choice {
    internal object Value; internal string Label;
    public override string ToString(){return Label;}
}
internal sealed class ConnectionInfo {
    internal bool Wifi; internal string Host,Key,Fingerprint,Serial;
    internal int VideoPort=5889,ControlPort=5891;
    internal bool Verify(X509Certificate certificate) {
        if(certificate==null||String.IsNullOrEmpty(Fingerprint)){return false;}
        using(var sha=SHA256.Create()){return BitConverter.ToString(sha.ComputeHash(certificate.GetRawCertData())).Replace("-","").Equals(Fingerprint,StringComparison.OrdinalIgnoreCase);}
    }
}
internal sealed class CamBridge : Form {
    private static readonly Color Ink=Color.FromArgb(20,35,51),PanelColor=Color.FromArgb(32,48,65),Sky=Color.FromArgb(130,200,255),Paper=Color.FromArgb(239,243,246),Muted=Color.FromArgb(170,185,199);
    private readonly PictureBox preview=new PictureBox();
    private readonly Label status=new Label(),metrics=new Label();
    private readonly ComboBox transport=new ComboBox(),orientation=new ComboBox();
    private readonly TextBox address=new TextBox(),serial=new TextBox();
    private readonly Button connect=new Button(),prepareWifi=new Button();
    private readonly CheckBox mirror=new CheckBox();
    private readonly TabControl tabs=new TabControl();
    private readonly Dictionary<string,Control> inputs=new Dictionary<string,Control>();
    private readonly Dictionary<string,object> pending=new Dictionary<string,object>();
    private readonly JavaScriptSerializer json=new JavaScriptSerializer();
    private readonly System.Windows.Forms.Timer timer=new System.Windows.Forms.Timer();
    private readonly byte[] previewBytes=new byte[960*960*4];
    private ConnectionInfo connection;
    private CancellationTokenSource cancellation;
    private Thread receiver;
    private TcpClient socket;
    private volatile bool connected,rawTransport;
    private double receivedFps,megabytesPerSecond;
    private bool updating,busy,stopping;
    private int tick; private volatile int rotationMode=-1;
    private volatile int sensorRotation=90,outputRotation=90,mirrored;
    private string schema="";
    private ulong lastFrame;
    private long lastFrameAt;
    private static readonly string DirectoryPath=AppDomain.CurrentDomain.BaseDirectory;

    [STAThread] private static void Main(){Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new CamBridge());}
    internal CamBridge(){
        Text="CamBridge";BackColor=Ink;ForeColor=Paper;Font=new Font("Segoe UI",10);MinimumSize=new Size(1050,720);Size=new Size(1240,840);StartPosition=FormStartPosition.CenterScreen;
        string icon=Path.Combine(DirectoryPath,"cambridge.ico");if(File.Exists(icon)){Icon=new Icon(icon);}
        var layout=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=2,RowCount=3,Padding=new Padding(22)};
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,65));layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,35));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,52));layout.RowStyles.Add(new RowStyle(SizeType.Absolute,108));layout.RowStyles.Add(new RowStyle(SizeType.Percent,100));Controls.Add(layout);
        var heading=new Label{Text="CamBridge",Font=new Font("Segoe UI",25,FontStyle.Bold),Dock=DockStyle.Fill};layout.Controls.Add(heading,0,0);
        var output=new Label{Text="Camera output: CamBridge Camera",ForeColor=Sky,Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleRight};layout.Controls.Add(output,1,0);
        var connectionPanel=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=4,RowCount=3,Padding=new Padding(0,8,0,8)};connectionPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,110));connectionPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));connectionPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,180));connectionPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,130));layout.Controls.Add(connectionPanel,0,1);layout.SetColumnSpan(connectionPanel,2);
        transport.DropDownStyle=ComboBoxStyle.DropDownList;transport.Items.AddRange(new object[]{"USB","Wi-Fi"});transport.SelectedIndex=0;transport.Dock=DockStyle.Fill;connectionPanel.Controls.Add(transport,0,0);
        address.TextChanged+=(s,e)=>{address.UseSystemPasswordChar=address.Text.StartsWith("cambridge://",StringComparison.OrdinalIgnoreCase);};address.Dock=DockStyle.Fill;address.Enabled=false;address.Text="Paste the connection link from your phone's Stream panel";connectionPanel.Controls.Add(address,1,0);
        serial.Dock=DockStyle.Fill;serial.AccessibleName="Optional USB device serial";connectionPanel.Controls.Add(serial,2,0);prepareWifi.Text="Prepare Wi-Fi";Style(prepareWifi);prepareWifi.Dock=DockStyle.Fill;prepareWifi.Visible=false;prepareWifi.Click+=(s,e)=>Queue("wireless",true);connectionPanel.Controls.Add(prepareWifi,2,0);
        connect.Text="Connect";Style(connect);connect.Dock=DockStyle.Fill;connect.Click+=(s,e)=>{if(cancellation!=null){Disconnect();}else{Connect();}};connectionPanel.Controls.Add(connect,3,0);
        status.Text="Open CamBridge on your phone, allow Camera, then tap Start camera. USB debugging is required for USB.";status.Dock=DockStyle.Fill;status.ForeColor=Muted;status.AutoEllipsis=true;connectionPanel.Controls.Add(status,0,1);connectionPanel.SetColumnSpan(status,4);
        metrics.Text="Video only · add microphone audio in your recording or meeting app";metrics.ForeColor=Sky;metrics.Dock=DockStyle.Fill;connectionPanel.Controls.Add(metrics,0,2);connectionPanel.SetColumnSpan(metrics,4);
        transport.SelectedIndexChanged+=(s,e)=>{address.Enabled=transport.SelectedIndex==1;serial.Enabled=transport.SelectedIndex==0;};
        var video=new Panel{Dock=DockStyle.Fill,BackColor=Color.Black,Margin=new Padding(0,0,18,0)};preview.Dock=DockStyle.Fill;preview.SizeMode=PictureBoxSizeMode.Zoom;preview.BackColor=Color.Black;video.Controls.Add(preview);layout.Controls.Add(video,0,2);preview.MouseClick+=FocusAt;
        var settings=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=4};settings.RowStyles.Add(new RowStyle(SizeType.Absolute,32));settings.RowStyles.Add(new RowStyle(SizeType.Absolute,36));settings.RowStyles.Add(new RowStyle(SizeType.Absolute,34));settings.RowStyles.Add(new RowStyle(SizeType.Percent,100));layout.Controls.Add(settings,1,2);
        settings.Controls.Add(new Label{Text="Camera controls",Font=new Font("Segoe UI",14,FontStyle.Bold),Dock=DockStyle.Fill},0,0);
        orientation.DropDownStyle=ComboBoxStyle.DropDownList;orientation.Items.AddRange(new object[]{"Orientation: automatic","Landscape · 0°","Portrait · 90°","Landscape · 180°","Portrait · 270°"});orientation.SelectedIndex=0;orientation.Dock=DockStyle.Fill;settings.Controls.Add(orientation,0,1);orientation.SelectedIndexChanged+=(s,e)=>{rotationMode=orientation.SelectedIndex==0?-1:(orientation.SelectedIndex-1)*90;};
        mirror.Text="Mirror camera output";mirror.Dock=DockStyle.Fill;mirror.CheckedChanged+=(s,e)=>{mirrored=mirror.Checked?1:0;};settings.Controls.Add(mirror,0,2);
        tabs.Dock=DockStyle.Fill;settings.Controls.Add(tabs,0,3);EmptyControls();
        timer.Interval=100;timer.Tick+=Tick;timer.Start();FormClosing+=(s,e)=>Disconnect();
    }
    private static void Style(Button button){button.BackColor=Sky;button.ForeColor=Ink;button.FlatStyle=FlatStyle.Flat;button.FlatAppearance.BorderSize=0;button.Font=new Font("Segoe UI",10,FontStyle.Bold);}
    private void EmptyControls(){tabs.TabPages.Clear();var tab=new TabPage("Camera"){BackColor=PanelColor,ForeColor=Paper};tab.Controls.Add(new Label{Text="Connect your phone to load the controls it supports.\n\nUse CamBridge Camera in your meeting or recording app. Keep this window open while using the camera.",Dock=DockStyle.Fill,Padding=new Padding(16)});tabs.TabPages.Add(tab);}
    private static void Adb(string arguments){
        var start=new ProcessStartInfo(Path.Combine(DirectoryPath,"adb","adb.exe"),arguments){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true};
        using(var process=Process.Start(start)){if(!process.WaitForExit(8000)){process.Kill();throw new IOException("ADB timed out. Unlock your phone and approve USB debugging.");}if(process.ExitCode!=0){throw new IOException(process.StandardError.ReadToEnd().Trim());}}
    }
    private ConnectionInfo ReadConnection(){
        var value=new ConnectionInfo{Wifi=transport.SelectedIndex==1,Host="127.0.0.1",Serial=serial.Text.Trim()};
        if(value.Wifi){
            Uri uri;if(!Uri.TryCreate(address.Text.Trim(),UriKind.Absolute,out uri)||uri.Scheme!="cambridge"){throw new ArgumentException("Paste the complete cambridge:// connection link from your phone. It includes the pairing key and certificate fingerprint.");}
            value.Host=uri.Host;value.VideoPort=5890;value.ControlPort=5892;
            var parameters=System.Web.HttpUtility.ParseQueryString(uri.Query);value.Key=parameters["key"];value.Fingerprint=parameters["fingerprint"];
            if(String.IsNullOrEmpty(value.Key)||value.Key.Length!=32||String.IsNullOrEmpty(value.Fingerprint)||value.Fingerprint.Length!=64){throw new ArgumentException("The connection link is incomplete. Copy it again from your phone.");}
            foreach(char c in value.Key+value.Fingerprint){if(!Uri.IsHexDigit(c)){throw new ArgumentException("Invalid pairing link.");}}
            IPAddress ip;if(!IPAddress.TryParse(value.Host,out ip)||ip.AddressFamily!=AddressFamily.InterNetwork){throw new ArgumentException("Use the phone's IPv4 connection link on the same Wi-Fi network.");}
        }else{
            foreach(char c in value.Serial){if(!Char.IsLetterOrDigit(c)&&c!='-'&&c!='_'&&c!='.'){throw new ArgumentException("Invalid USB serial.");}}
        }return value;
    }
    private void Connect(){
        try{connection=ReadConnection();}catch(Exception error){status.Text=error.Message;return;}
        cancellation=new CancellationTokenSource();var token=cancellation.Token;transport.Enabled=false;address.Enabled=false;serial.Enabled=false;connect.Text="Disconnect";status.Text="Connecting camera…";
        if(!connection.Wifi){
            connect.Enabled=false;Task.Run(()=>{string device=String.IsNullOrEmpty(connection.Serial)?"-d":"-s "+connection.Serial;Adb(device+" forward tcp:5889 tcp:5889");Adb(device+" forward tcp:5891 tcp:5891");}).ContinueWith(task=>BeginInvoke((Action)(()=>{connect.Enabled=true;if(task.IsFaulted){status.Text=task.Exception.GetBaseException().Message;Disconnect();}else if(!token.IsCancellationRequested){StartReceiver(token);}})));
        }else{StartReceiver(token);}
    }
    private void StartReceiver(CancellationToken token){receiver=new Thread(()=>Receive(token)){IsBackground=true,Name="CamBridge video"};receiver.SetApartmentState(ApartmentState.MTA);receiver.Start();}
    private static byte[] Exact(Stream input,int count){var bytes=new byte[count];int offset=0;while(offset<count){int read=input.Read(bytes,offset,count-offset);if(read==0){throw new EndOfStreamException("Phone disconnected");}offset+=read;}return bytes;}
    private static void Payload(Stream input,ref byte[] buffer,int length){
        if(buffer==null||buffer.Length<length){buffer=new byte[length];}int offset=0;while(offset<length){int read=input.Read(buffer,offset,length-offset);if(read==0){throw new EndOfStreamException("Phone disconnected");}offset+=read;}
    }
    private static uint Read32(byte[] bytes,int offset){return (uint)bytes[offset]<<24|(uint)bytes[offset+1]<<16|(uint)bytes[offset+2]<<8|bytes[offset+3];}
    private void Receive(CancellationToken token){
        while(!token.IsCancellationRequested){
            try{
                socket=new TcpClient();socket.NoDelay=true;socket.ReceiveBufferSize=64*1024;socket.ReceiveTimeout=3000;socket.SendTimeout=3000;
                var result=socket.BeginConnect(connection.Host,connection.VideoPort,null,null);if(!result.AsyncWaitHandle.WaitOne(3000)){throw new IOException("Cannot reach phone. Check USB authorization or the Wi-Fi link and network.");}socket.EndConnect(result);
                Stream stream=socket.GetStream();
                if(connection.Wifi){var tls=new SslStream(stream,false,(sender,certificate,chain,errors)=>connection.Verify(certificate));tls.AuthenticateAsClient("CamBridge Phone",null,SslProtocols.Tls12,false);stream=tls;byte[] key=Encoding.ASCII.GetBytes(connection.Key);stream.Write(new byte[]{0x43,0x42,0x47,0x32,32},0,5);stream.Write(key,0,key.Length);}
                stream.Write(new byte[]{0x44,0x41,0x50,0x32},0,4);Exact(stream,8);int advertisedMode=(int)Read32(Exact(stream,4),0);
                int hr=0;bool decoderOpened=false;byte[] payload=null;long sampleBytes=0;int sampleFrames=0;var rate=Stopwatch.StartNew();if(advertisedMode==0){hr=Native.CB_Open();if(hr<0){Marshal.ThrowExceptionForHR(hr);}decoderOpened=true;}stream.Write(new byte[]{0x52,0x44,0x59,0x21},0,4);connected=true;BeginInvoke((Action)(()=>{prepareWifi.Visible=!connection.Wifi;serial.Visible=connection.Wifi;}));PostStatus("Connected · choose CamBridge Camera in your other app. Tap the preview to focus.");
                while(!token.IsCancellationRequested){
                    byte[] header=Exact(stream,20);if(Read32(header,0)!=0x44414c54){throw new IOException("Protocol mismatch. Update the APK and Windows app together.");}
                    uint size=Read32(header,4),flags=Read32(header,16);if(size==0||size>32*1024*1024){throw new IOException("Invalid video packet");}
                    long pts=unchecked((long)((ulong)Read32(header,8)<<32|Read32(header,12)));Payload(stream,ref payload,(int)size);
                    if((flags&2)!=0){if(pts==0||pts==90||pts==180||pts==270){sensorRotation=(int)pts;}}
                    rawTransport=(flags&8)!=0;
                    if(rawTransport){sensorRotation=(int)Read32(payload,8);socket.ReceiveBufferSize=2*1024*1024;outputRotation=rotationMode<0?sensorRotation:rotationMode;hr=Native.CB_Raw(payload,(int)size,rotationMode,mirrored);}
                    else{if(!decoderOpened){hr=Native.CB_Open();if(hr<0){Marshal.ThrowExceptionForHR(hr);}decoderOpened=true;}outputRotation=rotationMode<0?sensorRotation:rotationMode;hr=Native.CB_Decode(payload,(int)size,(flags&2)!=0?0:pts,outputRotation,mirrored);}
                    sampleBytes+=size;if((flags&2)==0){sampleFrames++;}if(rate.ElapsedMilliseconds>=1000){receivedFps=sampleFrames/rate.Elapsed.TotalSeconds;megabytesPerSecond=sampleBytes/1000000.0/rate.Elapsed.TotalSeconds;sampleBytes=0;sampleFrames=0;rate.Restart();}
                    if(hr<0){throw new IOException("Decoder error 0x"+hr.ToString("X8")+". Try 720p/30 and update both apps.");}
                }
            }catch(Exception error){if(!token.IsCancellationRequested){PostStatus(error.Message+" · retrying…");}}
            finally{connected=false;if(socket!=null){socket.Close();socket=null;}Native.CB_Close();}
            token.WaitHandle.WaitOne(500);
        }
    }
    private void PostStatus(string message){if(!IsDisposed&&IsHandleCreated){try{BeginInvoke((Action)(()=>status.Text=message));}catch(InvalidOperationException){}}}
    private Dictionary<string,object> Request(string method,Dictionary<string,object> patch){
        string endpoint=(connection.Wifi?"https://":"http://")+connection.Host+":"+connection.ControlPort+(patch==null?"/state":"/control");
        var request=(HttpWebRequest)WebRequest.Create(endpoint);request.Method=method;request.Proxy=null;request.Timeout=2500;request.ReadWriteTimeout=2500;request.KeepAlive=false;
        if(connection.Wifi){request.ServerCertificateValidationCallback=(sender,certificate,chain,errors)=>connection.Verify(certificate);request.Headers["Authorization"]="Bearer "+connection.Key;}
        if(patch!=null){byte[] bytes=Encoding.UTF8.GetBytes(json.Serialize(patch));request.ContentType="application/json";request.ContentLength=bytes.Length;using(var output=request.GetRequestStream()){output.Write(bytes,0,bytes.Length);}}
        using(var response=request.GetResponse()){using(var reader=new StreamReader(response.GetResponseStream())){string body=reader.ReadToEnd();if(body.Length>128*1024){throw new IOException("Control response too large");}return json.Deserialize<Dictionary<string,object>>(body);}}
    }
    private void Tick(object sender,EventArgs args){
        if(stopping){return;}tick++;
        if(connected){
            int w,h;ulong count;double ms;int result=Native.CB_Preview(previewBytes,previewBytes.Length,out w,out h,out count,out ms);
            if(result>0&&count!=lastFrame){
                var bitmap=new Bitmap(w,h,PixelFormat.Format32bppArgb);var data=bitmap.LockBits(new Rectangle(0,0,w,h),ImageLockMode.WriteOnly,PixelFormat.Format32bppArgb);Marshal.Copy(previewBytes,0,data.Scan0,w*h*4);bitmap.UnlockBits(data);Image old=preview.Image;preview.Image=bitmap;if(old!=null){old.Dispose();}lastFrame=count;lastFrameAt=Stopwatch.GetTimestamp();
                metrics.Text=String.Format("{3} · {4:0.0} fps · {5:0.0} MB/s · PC frame work {2:0.0} ms",w,h,ms,rawTransport?"Uncompressed USB":Native.CB_Hardware()!=0?"GPU H.264":"CPU H.264",receivedFps,megabytesPerSecond);
            }else if(lastFrameAt!=0&&(Stopwatch.GetTimestamp()-lastFrameAt)/(double)Stopwatch.Frequency>2){metrics.Text="Waiting for fresh video · check the phone camera";}
        }
        if(cancellation==null||busy){return;}
        Dictionary<string,object> patch=null;if(pending.Count>0){patch=new Dictionary<string,object>(pending);pending.Clear();}else if(tick%20!=0){return;}
        busy=true;var token=cancellation.Token;Task.Run(()=>Request(patch==null?"GET":"POST",patch)).ContinueWith(task=>{if(IsDisposed||!IsHandleCreated){return;}try{BeginInvoke((Action)(()=>{busy=false;if(token.IsCancellationRequested){return;}if(task.IsFaulted){status.Text="Camera controls unavailable. Start/update the phone app and reconnect. "+task.Exception.GetBaseException().Message;}else{UpdateControls(task.Result);}}));}catch(InvalidOperationException){}});
    }
    private void Queue(string key,object value){if(!updating){pending[key]=value;}}
    private static object[] ArrayOf(object value){var array=value as object[];if(array!=null){return array;}var list=value as System.Collections.ArrayList;return list==null?new object[0]:list.ToArray();}
    private void UpdateControls(Dictionary<string,object> state){
        object raw;if(!state.TryGetValue("fields",out raw)){return;}
        object link;if(!connection.Wifi&&state.TryGetValue("wifiConnectionLink",out link)&&link is string){address.Text=(string)link;}object[] fields=ArrayOf(raw);string nextSchema="";
        foreach(object item in fields){var field=(Dictionary<string,object>)item;nextSchema+=field["key"]+":"+field["enabled"]+":"+(field.ContainsKey("options")?json.Serialize(field["options"]):"")+";";}
        updating=true;
        try{
            if(nextSchema!=schema){
                schema=nextSchema;inputs.Clear();tabs.TabPages.Clear();var groups=new Dictionary<string,FlowLayoutPanel>();
                foreach(object item in fields){
                    var def=(Dictionary<string,object>)item;string group=(string)def["group"],key=(string)def["key"],type=(string)def["type"];
                    FlowLayoutPanel panel;if(!groups.TryGetValue(group,out panel)){var tab=new TabPage(group){BackColor=PanelColor,ForeColor=Paper};panel=new FlowLayoutPanel{Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(12)};tab.Controls.Add(panel);tabs.TabPages.Add(tab);groups[group]=panel;}
                    var label=new Label{Text=(string)def["label"],ForeColor=Muted,AutoSize=true,Margin=new Padding(0,10,0,4)};panel.Controls.Add(label);Control input;
                    if(type=="boolean"){var check=new CheckBox{Text="Enabled",Width=265,Height=28};check.CheckedChanged+=(s,e)=>Queue(key,check.Checked);input=check;}
                    else if(type=="choice"){var choice=new ComboBox{DropDownStyle=ComboBoxStyle.DropDownList,Width=275};foreach(object optionItem in ArrayOf(def["options"])){var option=(Dictionary<string,object>)optionItem;choice.Items.Add(new Choice{Value=option["value"],Label=(string)option["label"]});}choice.SelectedIndexChanged+=(s,e)=>{if(choice.SelectedItem!=null){Queue(key,((Choice)choice.SelectedItem).Value);}};input=choice;}
                    else{var number=new NumericUpDown{Width=275,Minimum=Convert.ToDecimal(def["min"]),Maximum=Convert.ToDecimal(def["max"]),Increment=Convert.ToDecimal(def["step"]),DecimalPlaces=Convert.ToDouble(def["step"])<1?2:0};number.ValueChanged+=(s,e)=>Queue(key,number.Value);input=number;}
                    input.Enabled=Convert.ToBoolean(def["enabled"]);panel.Controls.Add(input);inputs[key]=input;
                }
            }
            foreach(object item in fields){var def=(Dictionary<string,object>)item;Control input;if(!inputs.TryGetValue((string)def["key"],out input)||input.Focused||pending.ContainsKey((string)def["key"])){continue;}object value=def["value"];if((string)def["key"]=="fps"){Native.CB_Rate(Convert.ToInt32(value));}var check=input as CheckBox;if(check!=null){check.Checked=Convert.ToBoolean(value);}var number=input as NumericUpDown;if(number!=null){number.Value=Math.Max(number.Minimum,Math.Min(number.Maximum,Convert.ToDecimal(value)));}var choice=input as ComboBox;if(choice!=null){for(int i=0;i<choice.Items.Count;i++){if(Convert.ToString(((Choice)choice.Items[i]).Value)==Convert.ToString(value)){choice.SelectedIndex=i;break;}}}}
        }finally{updating=false;}
    }
    private void FocusAt(object sender,MouseEventArgs args){
        if(!connected||preview.Image==null){return;}double scale=Math.Min(preview.Width/(double)preview.Image.Width,preview.Height/(double)preview.Image.Height);double w=preview.Image.Width*scale,h=preview.Image.Height*scale,x=(args.X-(preview.Width-w)/2)/w,y=(args.Y-(preview.Height-h)/2)/h;if(x<0||x>1||y<0||y>1){return;}if(mirrored!=0){x=1-x;}double sx=x,sy=y;if(outputRotation==90){sx=y;sy=1-x;}else if(outputRotation==180){sx=1-x;sy=1-y;}else if(outputRotation==270){sx=1-y;sy=x;}Queue("focusX",sx);Queue("focusY",sy);
    }
    private void Disconnect(){
        stopping=true;if(cancellation!=null){cancellation.Cancel();}if(socket!=null){socket.Close();}if(receiver!=null){receiver.Join(4000);receiver=null;}if(cancellation!=null){cancellation.Dispose();cancellation=null;}connected=false;pending.Clear();connect.Text="Connect";connect.Enabled=true;stopping=false;busy=false;prepareWifi.Visible=false;serial.Visible=true;transport.Enabled=true;address.Enabled=transport.SelectedIndex==1;serial.Enabled=transport.SelectedIndex==0;schema="";EmptyControls();metrics.Text="Disconnected · camera output shows the CamBridge standby screen";
    }
}
