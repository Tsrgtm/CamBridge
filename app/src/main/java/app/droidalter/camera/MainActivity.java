// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (c) 2026 CamBridge contributors
package app.droidalter.camera;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int BASE=Color.rgb(20,22,25), PANEL=Color.rgb(35,38,43), TEXT=Color.rgb(239,243,246), MUTED=Color.rgb(157,168,181), BLUE=Color.rgb(130,200,255);
    private final Handler main=new Handler(Looper.getMainLooper());
    private CameraManager manager;
    private CameraService service;
    private CameraEngine engine;
    private CameraControls controls=new CameraControls();
    private List<CameraCatalog.Lens> lenses=new ArrayList<>();
    private final List<Size> sizes=new ArrayList<>();
    private final List<Integer> rates=new ArrayList<>(), focusValues=new ArrayList<>(), wbValues=new ArrayList<>(), isoValues=new ArrayList<>(), noiseValues=new ArrayList<>(), edgeValues=new ArrayList<>();
    private final List<Long> shutterValues=new ArrayList<>();
    private final int[] bitrates={8,16,24,40,60};
    private final List<LinearLayout> panels=new ArrayList<>();
    private final List<Button> tabButtons=new ArrayList<>(), lensButtons=new ArrayList<>();
    private LinearLayout lensRow;
    private TextureView preview;
    private CameraOverlay overlay;
    private TextView status,summary,zoomLabel,evLabel,focusLabel,controlHint;
    private Button start;
    private Spinner lensChoice,sizeChoice,fpsChoice,bitrateChoice,focusChoice,isoChoice,shutterChoice,wbChoice,noiseChoice,edgeChoice;
    private SeekBar zoomSlider,evSlider,focusSlider;
    private Switch torchSwitch,autoExposureSwitch,aeLockSwitch,wbLockSwitch,oisSwitch,eisSwitch;
    private boolean bound,ready,uiUpdating,streaming;
    private int lastLens=-1,lastSize=-1;
    private float minimumZoom=1,maximumZoom=8,maximumFocus,previewLeft,previewTop,previewWidth,previewHeight;
    private final Runnable applyStream=this::applyStreamSettings;
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){
            service=((CameraService.LocalBinder)binder).service();engine=service.engine();
            service.setListener(new CameraEngine.Listener(){
                public void status(String message){runOnUiThread(()->status.setText(message));}
                public void failed(String message){runOnUiThread(()->{setStreaming(false);status.setText(message);});}
            });
            if(service.isStreaming()){restoreServiceSettings();}else{service.setControls(controls);}
            setStreaming(service.isStreaming());status.setText(service.status());
            if(streaming&&preview.isAvailable()){engine.attachPreview(preview.getSurfaceTexture());}
        }
        public void onServiceDisconnected(ComponentName name){service=null;engine=null;}
    };

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);manager=(CameraManager)getSystemService(CAMERA_SERVICE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);getWindow().setStatusBarColor(BASE);getWindow().setNavigationBarColor(BASE);
        buildInterface();bound=bindService(new Intent(this,CameraService.class),connection,BIND_AUTO_CREATE);
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){discoverCameras();}
        else{requestPermissions(new String[]{Manifest.permission.CAMERA},1);}
    }

    private void buildInterface(){
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BASE);scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout content=column();content.setPadding(dp(16),dp(12),dp(16),dp(28));scroll.addView(content);setContentView(scroll);
        LinearLayout header=row();TextView title=text("CamBridge",22,TEXT);title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));summary=text("USB camera",13,BLUE);header.addView(summary);content.addView(header);
        status=text("Connect your phone camera to OBS",13,MUTED);status.setPadding(0,dp(7),0,dp(12));content.addView(status);
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);preview=new TextureView(this);preview.setOpaque(false);preview.setSurfaceTextureListener(this);
        frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));overlay=new CameraOverlay();frame.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        content.addView(frame,new LinearLayout.LayoutParams(-1,dp(236)));wirePreviewGestures();
        HorizontalScrollView lensScroll=new HorizontalScrollView(this);lensScroll.setHorizontalScrollBarEnabled(false);lensRow=row();lensScroll.addView(lensRow);content.addView(lensScroll);
        zoomLabel=text("Zoom 1.0×",14,TEXT);zoomLabel.setPadding(0,dp(8),0,0);content.addView(zoomLabel);zoomSlider=slider(content,1000);
        zoomSlider.setOnSeekBarChangeListener(new SliderListener(){public void onProgressChanged(SeekBar b,int p,boolean user){
            if(uiUpdating){return;}controls.zoom=(float)(minimumZoom*Math.pow(maximumZoom/minimumZoom,p/1000.0));zoomLabel.setText(String.format(Locale.US,"Zoom %.2f×",controls.zoom));sendControls();}});
        start=button("Start USB camera");start.setTextColor(BASE);start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));content.addView(start,new LinearLayout.LayoutParams(-1,dp(48)));start.setOnClickListener(view->toggleStreaming());
        LinearLayout tabs=row();tabs.setPadding(0,dp(14),0,dp(10));content.addView(tabs);
        for(String name:new String[]{"Camera","Exposure","Color","Stream"}){
            int index=tabButtons.size();Button tab=button(name);tab.setTextSize(12);tab.setPadding(0,0,0,0);tabs.addView(tab,new LinearLayout.LayoutParams(0,dp(42),1));tab.setOnClickListener(view->selectPanel(index));tabButtons.add(tab);
            LinearLayout panel=column();panel.setPadding(dp(12),dp(8),dp(12),dp(12));panel.setBackgroundColor(PANEL);panels.add(panel);
        }
        for(LinearLayout panel:panels){content.addView(panel);}
        LinearLayout camera=panels.get(0),exposure=panels.get(1),color=panels.get(2),stream=panels.get(3);
        lensChoice=spinner(camera,"Camera and physical lens");controlHint=text("Tap the preview to focus. Pinch or drag to zoom.",12,MUTED);camera.addView(controlHint);
        focusChoice=spinner(camera,"Focus mode");focusLabel=text("Manual focus · infinity",13,TEXT);camera.addView(focusLabel);focusSlider=slider(camera,1000);
        focusSlider.setOnSeekBarChangeListener(new SliderListener(){public void onProgressChanged(SeekBar b,int p,boolean user){if(uiUpdating){return;}controls.focusDistance=maximumFocus*p/1000f;focusLabel.setText(controls.focusDistance<.01f?"Manual focus · infinity":String.format(Locale.US,"Manual focus · %.2f diopters",controls.focusDistance));sendControls();}});
        torchSwitch=toggle(camera,"Torch");oisSwitch=toggle(camera,"Optical stabilization");eisSwitch=toggle(camera,"Video stabilization");Switch grid=toggle(camera,"Composition grid");grid.setOnCheckedChangeListener((v,value)->{overlay.grid=value;overlay.invalidate();});
        autoExposureSwitch=toggle(exposure,"Automatic exposure");aeLockSwitch=toggle(exposure,"Lock auto exposure");evLabel=text("Exposure compensation 0",14,TEXT);exposure.addView(evLabel);evSlider=slider(exposure,1);
        evSlider.setOnSeekBarChangeListener(new SliderListener(){public void onProgressChanged(SeekBar b,int p,boolean user){if(uiUpdating){return;}controls.exposureCompensation=p-(b.getTag() instanceof Integer?(Integer)b.getTag():0);evLabel.setText("Exposure compensation "+controls.exposureCompensation+" steps");sendControls();}});
        isoChoice=spinner(exposure,"ISO sensitivity");shutterChoice=spinner(exposure,"Shutter speed");exposure.addView(text("Manual exposure is limited to the sensor range and current frame rate.",12,MUTED));
        wbChoice=spinner(color,"White balance");wbLockSwitch=toggle(color,"Lock white balance");noiseChoice=spinner(color,"Noise reduction");edgeChoice=spinner(color,"Edge enhancement");color.addView(text("Fast processing keeps latency low. High quality processing can add sensor processing time.",12,MUTED));
        sizeChoice=spinner(stream,"Video resolution");fpsChoice=spinner(stream,"Frame rate");bitrateChoice=spinner(stream,"Video bitrate");populate(bitrateChoice,Arrays.asList("8 Mbps","16 Mbps","24 Mbps","40 Mbps","60 Mbps"));bitrateChoice.setSelection(2);
        stream.addView(text("Changes apply while streaming. Lens, resolution and frame-rate changes briefly restart capture; OBS recording stays active. Bitrate and camera controls update live.\n\nStart the camera while unlocked. Streaming continues with the screen off. Stop from the notification or this button. Your microphone stays in OBS.",13,MUTED));
        wireControls();selectPanel(0);start.setEnabled(false);
    }

    private void wirePreviewGestures(){
        ScaleGestureDetector pinch=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){setZoom(controls.zoom*d.getScaleFactor());return true;}});
        GestureDetector taps=new GestureDetector(this,new GestureDetector.SimpleOnGestureListener(){
            public boolean onDown(MotionEvent event){return true;}
            public boolean onSingleTapConfirmed(MotionEvent event){
                if(!streaming||engine==null||previewWidth<=0||previewHeight<=0){return true;}
                float x=(event.getX()-previewLeft)/previewWidth,y=(event.getY()-previewTop)/previewHeight;if(x<0||x>1||y<0||y>1){return true;}
                int orientation=orientation();float sensorX=x,sensorY=y;if(orientation==90){sensorX=y;sensorY=1-x;}else if(orientation==270){sensorX=1-y;sensorY=x;}else if(orientation==180){sensorX=1-x;sensorY=1-y;}
                int tapMode=focusValues.indexOf(CaptureRequest.CONTROL_AF_MODE_AUTO);if(tapMode>=0){focusChoice.setSelection(tapMode);}engine.focusAt(sensorX,sensorY);overlay.focus(event.getX(),event.getY());return true;
            }
        });
        preview.setOnTouchListener((view,event)->{pinch.onTouchEvent(event);if(!pinch.isInProgress()){taps.onTouchEvent(event);}return true;});
    }

    private void wireControls(){
        lensChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!ready||uiUpdating||p==lastLens){return;}lastLens=p;loadCapabilities(false);scheduleStreamUpdate();}});
        sizeChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!ready||uiUpdating||p==lastSize){return;}lastSize=p;loadFrameRates(30);loadShutters();updatePreview();scheduleStreamUpdate();}});
        fpsChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!uiUpdating){loadShutters();scheduleStreamUpdate();}}});
        bitrateChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!uiUpdating){scheduleStreamUpdate();}}});
        focusChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(uiUpdating||p>=focusValues.size()){return;}controls.focusMode=focusValues.get(p);focusSlider.setEnabled(controls.focusMode==0&&maximumFocus>0);sendControls();}});
        isoChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!uiUpdating&&p<isoValues.size()){controls.iso=isoValues.get(p);sendControls();}}});
        shutterChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!uiUpdating&&p<shutterValues.size()){controls.shutterNs=shutterValues.get(p);sendControls();}}});
        wbChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!uiUpdating&&p<wbValues.size()){controls.whiteBalance=wbValues.get(p);sendControls();}}});
        noiseChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!uiUpdating&&p<noiseValues.size()){controls.noiseReduction=noiseValues.get(p);sendControls();}}});
        edgeChoice.setOnItemSelectedListener(new Selection(){public void onItemSelected(AdapterView<?> a,View v,int p,long id){if(!uiUpdating&&p<edgeValues.size()){controls.edgeEnhancement=edgeValues.get(p);sendControls();}}});
        torchSwitch.setOnCheckedChangeListener((v,value)->{if(!uiUpdating){controls.torch=value;sendControls();}});
        autoExposureSwitch.setOnCheckedChangeListener((v,value)->{if(!uiUpdating){controls.autoExposure=value;updateExposureAvailability();sendControls();}});
        aeLockSwitch.setOnCheckedChangeListener((v,value)->{if(!uiUpdating){controls.exposureLock=value;sendControls();}});wbLockSwitch.setOnCheckedChangeListener((v,value)->{if(!uiUpdating){controls.whiteBalanceLock=value;sendControls();}});
        oisSwitch.setOnCheckedChangeListener((v,value)->{if(!uiUpdating){controls.opticalStabilization=value;sendControls();}});eisSwitch.setOnCheckedChangeListener((v,value)->{if(!uiUpdating){controls.videoStabilization=value;sendControls();}});
    }

    private void discoverCameras(){
        try{
            lenses=CameraCatalog.discover(manager);if(lenses.isEmpty()){status.setText("No video camera is exposed by this phone.");return;}
            uiUpdating=true;List<String> labels=new ArrayList<>();for(CameraCatalog.Lens lens:lenses){labels.add(lens.title);}populate(lensChoice,labels);lensRow.removeAllViews();lensButtons.clear();
            for(int i=0;i<lenses.size();i++){final int index=i;Button chip=button(lenses.get(i).shortName);chip.setTextSize(12);chip.setPadding(0,0,0,0);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(dp(58),dp(44));layout.setMargins(dp(2),dp(8),dp(2),0);lensRow.addView(chip,layout);chip.setOnClickListener(v->lensChoice.setSelection(index));lensButtons.add(chip);}
            String previous=getPreferences(MODE_PRIVATE).getString("lens",lenses.get(0).key);lastLens=0;for(int i=0;i<lenses.size();i++){if(lenses.get(i).key.equals(previous)){lastLens=i;break;}}
            lensChoice.setSelection(lastLens);ready=true;uiUpdating=false;loadCapabilities(false);status.setText("Ready · "+lenses.size()+" camera routes available");start.setEnabled(preview.isAvailable());
        }catch(Exception error){uiUpdating=false;status.setText("Camera discovery: "+error.getMessage());}
    }
    private CameraCatalog.Lens lens(){return lenses.get(Math.max(0,lensChoice.getSelectedItemPosition()));}
    private CameraCharacteristics capabilities(){return lens().characteristics;}
    private int orientation(){Integer value=capabilities().get(CameraCharacteristics.SENSOR_ORIENTATION);return value==null?90:value;}

    private void loadCapabilities(boolean preserve){
        if(!ready){return;}uiUpdating=true;
        try{
            CameraCharacteristics c=capabilities();StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(map==null){map=manager.getCameraCharacteristics(lens().cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);}
            Size[] supported=map==null?null:map.getOutputSizes(MediaCodec.class);sizes.clear();List<String> labels=new ArrayList<>();if(supported!=null){for(Size size:supported){if(size.getWidth()>=640&&size.getWidth()<=3840&&size.getHeight()>=480&&size.getHeight()<=2160){sizes.add(size);}}}
            sizes.sort((a,b)->Long.compare((long)b.getWidth()*b.getHeight(),(long)a.getWidth()*a.getHeight()));int selected=0;Size preferred=preserve&&service!=null?service.captureSize():new Size(1920,1080);
            for(int i=0;i<sizes.size();i++){Size size=sizes.get(i);labels.add(size.getWidth()+" × "+size.getHeight());if(size.equals(preferred)){selected=i;}}populate(sizeChoice,labels);lastSize=selected;sizeChoice.setSelection(selected);
            Range<Float> ratio=Build.VERSION.SDK_INT>=30?c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE):null;Float digital=c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            minimumZoom=lens().physicalId==null&&ratio!=null?ratio.getLower():1f;maximumZoom=Math.max(minimumZoom,Math.min(30f,lens().physicalId==null&&ratio!=null?ratio.getUpper():digital==null?1:digital));
            if(!preserve){controls.zoom=1;controls.exposureCompensation=0;controls.torch=false;}controls.zoom=Math.max(minimumZoom,Math.min(maximumZoom,controls.zoom));zoomSlider.setProgress(zoomProgress(controls.zoom));zoomLabel.setText(String.format(Locale.US,"Zoom %.2f×",controls.zoom));zoomSlider.setEnabled(maximumZoom>minimumZoom);
            Range<Integer> ev=c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);int lower=ev==null?0:ev.getLower(),upper=ev==null?0:ev.getUpper();controls.exposureCompensation=Math.max(lower,Math.min(upper,controls.exposureCompensation));evSlider.setTag(-lower);evSlider.setMax(upper-lower);evSlider.setProgress(controls.exposureCompensation-lower);evLabel.setText("Exposure compensation "+controls.exposureCompensation+" steps");
            maximumFocus=Objects.requireNonNullElse(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),0f);fillModes(focusChoice,focusValues,c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),new int[]{3,1,0},new String[]{"Continuous autofocus","Tap to focus / hold","Manual focus"},controls.focusMode);if(!focusValues.contains(controls.focusMode)&&!focusValues.isEmpty()){controls.focusMode=focusValues.get(0);}focusSlider.setProgress(maximumFocus>0?(int)(controls.focusDistance/maximumFocus*1000):0);focusSlider.setEnabled(controls.focusMode==0&&maximumFocus>0);
            torchSwitch.setEnabled(Boolean.TRUE.equals(manager.getCameraCharacteristics(lens().cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)));torchSwitch.setChecked(controls.torch);
            boolean manual=contains(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);if(!manual){controls.autoExposure=true;}autoExposureSwitch.setEnabled(manual);autoExposureSwitch.setChecked(controls.autoExposure);aeLockSwitch.setChecked(controls.exposureLock);
            isoValues.clear();List<String> isoLabels=new ArrayList<>();Range<Integer> sensitivity=c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);for(int value:new int[]{50,100,200,400,800,1600,3200,6400}){if(sensitivity!=null&&sensitivity.contains(value)){isoValues.add(value);isoLabels.add(Integer.toString(value));}}populate(isoChoice,isoLabels);isoChoice.setSelection(Math.max(0,isoValues.indexOf(controls.iso)));
            fillModes(wbChoice,wbValues,c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),new int[]{1,5,6,8,2,3,4,7},new String[]{"Auto","Daylight","Cloudy","Shade","Tungsten","Fluorescent","Warm fluorescent","Twilight"},controls.whiteBalance);wbLockSwitch.setEnabled(Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)));wbLockSwitch.setChecked(controls.whiteBalanceLock);
            fillModes(noiseChoice,noiseValues,c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),new int[]{0,1,2},new String[]{"Off","Fast · low latency","High quality"},controls.noiseReduction);fillModes(edgeChoice,edgeValues,c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),new int[]{0,1,2},new String[]{"Off","Fast · low latency","High quality"},controls.edgeEnhancement);
            oisSwitch.setEnabled(contains(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),1));oisSwitch.setChecked(controls.opticalStabilization);eisSwitch.setEnabled(contains(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),1));eisSwitch.setChecked(controls.videoStabilization);
            loadFrameRates(preserve&&service!=null?service.captureFps():30);loadShutters();updateExposureAvailability();updatePreview();controlHint.setText(lens().title+"\nTap the preview to focus. Pinch or drag to zoom.");
            for(int i=0;i<lensButtons.size();i++){lensButtons.get(i).setTextColor(i==lensChoice.getSelectedItemPosition()?BASE:TEXT);lensButtons.get(i).setBackgroundTintList(android.content.res.ColorStateList.valueOf(i==lensChoice.getSelectedItemPosition()?BLUE:PANEL));}
        }catch(Exception error){status.setText("Camera settings: "+error.getMessage());}finally{uiUpdating=false;}sendControls();
    }

    private void loadFrameRates(Integer preferred){
        if(!ready||sizes.isEmpty()){return;}boolean previous=uiUpdating;uiUpdating=true;
        try{CameraCharacteristics c=capabilities();StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);Size size=sizes.get(Math.max(0,sizeChoice.getSelectedItemPosition()));long duration=map==null?0:map.getOutputMinFrameDuration(MediaCodec.class,size);Range<Integer>[] ranges=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);rates.clear();List<String> labels=new ArrayList<>();
            for(int fps:new int[]{24,25,30,60}){if(duration>0&&duration>1_000_000_000L/fps+100_000){continue;}if(ranges!=null){for(Range<Integer> range:ranges){if(range.getUpper()==fps){rates.add(fps);labels.add(fps+" fps");break;}}}}
            populate(fpsChoice,labels);fpsChoice.setSelection(Math.max(0,rates.indexOf(preferred==null?30:preferred)));start.setEnabled(preview.isAvailable()&&!rates.isEmpty());
        }finally{uiUpdating=previous;}
    }
    private void loadShutters(){
        if(!ready){return;}boolean previous=uiUpdating;uiUpdating=true;shutterValues.clear();List<String> labels=new ArrayList<>();Range<Long> range=capabilities().get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);int fps=rates.isEmpty()?30:rates.get(Math.max(0,fpsChoice.getSelectedItemPosition()));int selected=0;
        for(int denominator:new int[]{8000,4000,2000,1000,500,250,120,100,60,50,30,25,24}){long value=1_000_000_000L/denominator;if(denominator<fps||range==null||!range.contains(value)){continue;}if(Math.abs(value-controls.shutterNs)<1000){selected=shutterValues.size();}shutterValues.add(value);labels.add("1/"+denominator+" s");}
        populate(shutterChoice,labels);shutterChoice.setSelection(selected);if(!shutterValues.isEmpty()){controls.shutterNs=shutterValues.get(selected);}uiUpdating=previous;updateSummary();
    }
    private void fillModes(Spinner spinner,List<Integer> values,int[] available,int[] wanted,String[] names,int selected){values.clear();List<String> labels=new ArrayList<>();for(int i=0;i<wanted.length;i++){if(contains(available,wanted[i])){values.add(wanted[i]);labels.add(names[i]);}}populate(spinner,labels);spinner.setSelection(Math.max(0,values.indexOf(selected)));spinner.setEnabled(!values.isEmpty());}
    private boolean contains(int[] values,int desired){if(values!=null){for(int value:values){if(value==desired){return true;}}}return false;}
    private void updateExposureAvailability(){isoChoice.setEnabled(!controls.autoExposure&&!isoValues.isEmpty());shutterChoice.setEnabled(!controls.autoExposure&&!shutterValues.isEmpty());evSlider.setEnabled(controls.autoExposure&&evSlider.getMax()>0);if(ready){aeLockSwitch.setEnabled(controls.autoExposure&&Boolean.TRUE.equals(capabilities().get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)));}}
    private int zoomProgress(float zoom){return maximumZoom>minimumZoom?(int)(Math.log(zoom/minimumZoom)/Math.log(maximumZoom/minimumZoom)*1000):0;}
    private void setZoom(float value){if(ready){zoomSlider.setProgress(zoomProgress(Math.max(minimumZoom,Math.min(maximumZoom,value))));}}
    private void sendControls(){if(uiUpdating){return;}if(service!=null){service.setControls(controls);}if(ready){getPreferences(MODE_PRIVATE).edit().putString("lens",lens().key).apply();}}
    private void scheduleStreamUpdate(){updateSummary();if(uiUpdating||!streaming){return;}main.removeCallbacks(applyStream);main.postDelayed(applyStream,450);}
    private void applyStreamSettings(){if(service==null||!streaming||!ready||sizes.isEmpty()||rates.isEmpty()||!preview.isAvailable()){return;}service.setControls(controls);service.updateCapture(lens(),sizes.get(sizeChoice.getSelectedItemPosition()),rates.get(fpsChoice.getSelectedItemPosition()),bitrates[bitrateChoice.getSelectedItemPosition()],preview.getSurfaceTexture());updatePreview();}
    private void toggleStreaming(){
        if(service==null){status.setText("Preparing camera service…");return;}if(streaming){main.removeCallbacks(applyStream);service.stopCapture();setStreaming(false);return;}if(!ready||!preview.isAvailable()||sizes.isEmpty()||rates.isEmpty()){return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},2);}
        try{service.setControls(controls);service.startCapture(lens(),sizes.get(sizeChoice.getSelectedItemPosition()),rates.get(fpsChoice.getSelectedItemPosition()),bitrates[bitrateChoice.getSelectedItemPosition()],preview.getSurfaceTexture());setStreaming(true);}catch(Exception error){status.setText("Camera service: "+error.getMessage());setStreaming(false);}
    }
    private void setStreaming(boolean value){streaming=value;start.setText(value?"Stop USB camera":"Start USB camera");summary.setTextColor(value?BLUE:MUTED);updateSummary();}
    private void updateSummary(){if(ready&&!sizes.isEmpty()&&!rates.isEmpty()){Size size=sizes.get(Math.max(0,sizeChoice.getSelectedItemPosition()));int fps=rates.get(Math.max(0,fpsChoice.getSelectedItemPosition()));summary.setText((size.getWidth()==3840?"4K":size.getHeight()+"p")+" / "+fps+" fps");}}
    private void restoreServiceSettings(){if(service==null||!ready||service.captureLens()==null){return;}controls=service.controls();uiUpdating=true;for(int i=0;i<lenses.size();i++){if(lenses.get(i).key.equals(service.captureLens().key)){lastLens=i;lensChoice.setSelection(i);break;}}bitrateChoice.setSelection(Math.max(0,indexOf(bitrates,service.captureBitrate())));uiUpdating=false;loadCapabilities(true);}
    private int indexOf(int[] values,int value){for(int i=0;i<values.length;i++){if(values[i]==value){return i;}}return -1;}
    private void updatePreview(){if(!ready||preview.getWidth()==0||sizes.isEmpty()){return;}Size size=sizes.get(Math.max(0,sizeChoice.getSelectedItemPosition()));float w=preview.getWidth(),h=preview.getHeight();int rotation=orientation();float ratio=rotation==90||rotation==270?(float)size.getHeight()/size.getWidth():(float)size.getWidth()/size.getHeight();previewWidth=Math.min(w,h*ratio);previewHeight=previewWidth/ratio;previewLeft=(w-previewWidth)/2;previewTop=(h-previewHeight)/2;Matrix matrix=new Matrix();matrix.setRectToRect(new RectF(0,0,w,h),new RectF(previewLeft,previewTop,previewLeft+previewWidth,previewTop+previewHeight),Matrix.ScaleToFit.FILL);preview.setTransform(matrix);overlay.invalidate();}
    private void selectPanel(int selected){for(int i=0;i<panels.size();i++){panels.get(i).setVisibility(i==selected?View.VISIBLE:View.GONE);tabButtons.get(i).setTextColor(i==selected?BASE:MUTED);tabButtons.get(i).setBackgroundTintList(android.content.res.ColorStateList.valueOf(i==selected?BLUE:PANEL));}}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private LinearLayout row(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.HORIZONTAL);layout.setGravity(Gravity.CENTER_VERTICAL);return layout;}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
    private Button button(String title){Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setTextColor(TEXT);return button;}
    private Spinner spinner(LinearLayout parent,String title){TextView label=text(title,13,MUTED);label.setPadding(0,dp(12),0,dp(2));parent.addView(label);Spinner spinner=new Spinner(this);parent.addView(spinner,new LinearLayout.LayoutParams(-1,dp(48)));return spinner;}
    private void populate(Spinner spinner,List<String> values){ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);}
    private SeekBar slider(LinearLayout parent,int maximum){SeekBar slider=new SeekBar(this);slider.setMax(maximum);parent.addView(slider,new LinearLayout.LayoutParams(-1,dp(40)));return slider;}
    private Switch toggle(LinearLayout parent,String title){Switch view=new Switch(this);view.setText(title);view.setTextColor(TEXT);view.setTextSize(14);view.setPadding(0,dp(10),0,dp(10));parent.addView(view);return view;}
    private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density);}
    private abstract static class Selection implements AdapterView.OnItemSelectedListener{public void onNothingSelected(AdapterView<?> view){}}
    private abstract static class SliderListener implements SeekBar.OnSeekBarChangeListener{public void onStartTrackingTouch(SeekBar view){}public void onStopTrackingTouch(SeekBar view){}}
    private final class CameraOverlay extends View{
        boolean grid;float focusX=-1,focusY;Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);CameraOverlay(){super(MainActivity.this);setClickable(false);}
        void focus(float x,float y){focusX=x;focusY=y;invalidate();main.postDelayed(()->{focusX=-1;invalidate();},1200);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));if(grid){paint.setColor(0x70ffffff);for(int i=1;i<=2;i++){float x=previewLeft+previewWidth*i/3,y=previewTop+previewHeight*i/3;canvas.drawLine(x,previewTop,x,previewTop+previewHeight,paint);canvas.drawLine(previewLeft,y,previewLeft+previewWidth,y,paint);}}if(focusX>=0){paint.setColor(Color.rgb(249,182,77));float half=dp(20);canvas.drawRect(focusX-half,focusY-half,focusX+half,focusY+half,paint);}}
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==1){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){discoverCameras();}else{status.setText("Allow camera access to send video to OBS.");}}}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){start.setEnabled(ready&&!rates.isEmpty());updatePreview();if(engine!=null&&service!=null&&service.isStreaming()){engine.attachPreview(surface);}}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){updatePreview();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){if(engine!=null&&service!=null&&service.isStreaming()){engine.detachPreview();return false;}return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){}
    @Override public void onStop(){main.removeCallbacks(applyStream);if(engine!=null&&service!=null&&service.isStreaming()){engine.detachPreview();}super.onStop();}
    @Override public void onResume(){super.onResume();if(service!=null){setStreaming(service.isStreaming());status.setText(service.status());if(engine!=null&&streaming&&preview.isAvailable()){engine.attachPreview(preview.getSurfaceTexture());}}}
    @Override public void onDestroy(){main.removeCallbacks(applyStream);if(service!=null){service.setListener(null);}if(bound){unbindService(connection);}super.onDestroy();}
}
