// SPDX-License-Identifier: GPL-2.0-or-later
package app.droidalter.camera;

import android.hardware.camera2.*;
import android.media.MediaCodec;
import android.util.*;
import org.json.*;
import java.lang.reflect.Field;
import java.util.*;

/** One capability schema drives the PC controls; no manufacturer names or sensor IDs. */
final class CameraRemote {
    private final CameraService service;
    private final CameraManager manager;
    CameraRemote(CameraService service){this.service=service;manager=(CameraManager)service.getSystemService(android.content.Context.CAMERA_SERVICE);}
    private static boolean has(int[] values,int wanted){if(values!=null){for(int value:values){if(value==wanted){return true;}}}return false;}
    private static JSONObject option(Object value,String label)throws JSONException{return new JSONObject().put("value",value).put("label",label);}
    private static JSONObject setting(String key,String label,String group,Object value,String type,boolean enabled)throws JSONException{return new JSONObject().put("key",key).put("label",label).put("group",group).put("value",value).put("type",type).put("enabled",enabled);}
    private static void number(JSONArray list,String key,String label,String group,double value,double low,double high,double step,boolean enabled)throws JSONException{list.put(setting(key,label,group,value,"number",enabled).put("min",low).put("max",Math.max(low,high)).put("step",step));}
    private static void toggle(JSONArray list,String key,String label,String group,boolean value,boolean enabled)throws JSONException{list.put(setting(key,label,group,value,"boolean",enabled));}
    private static void modes(JSONArray list,String key,String label,String group,int value,int[] available,String[] names)throws JSONException{
        JSONArray options=new JSONArray();if(available!=null){for(int mode:available){options.put(option(mode,mode>=0&&mode<names.length?names[mode]:Integer.toString(mode)));}}
        list.put(setting(key,label,group,value,"choice",options.length()>0).put("options",options));
    }
    private List<Size> sizes(CameraCharacteristics c){
        android.hardware.camera2.params.StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] found=map==null?null:(service.rawMode()?map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888):map.getOutputSizes(MediaCodec.class));List<Size> sizes=new ArrayList<>();
        if(found!=null){for(Size size:found){if(size.getWidth()<=3840&&size.getHeight()<=2160&&size.getWidth()%2==0&&size.getHeight()%2==0){sizes.add(size);}}}
        sizes.sort(Comparator.comparingLong(s->-(long)s.getWidth()*s.getHeight()));return sizes;
    }
    private List<Integer> rates(CameraCharacteristics c,Size size){
        TreeSet<Integer> result=new TreeSet<>();Range<Integer>[] ranges=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        android.hardware.camera2.params.StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        long duration=map==null?0:(service.rawMode()?map.getOutputMinFrameDuration(android.graphics.ImageFormat.YUV_420_888,size):map.getOutputMinFrameDuration(MediaCodec.class,size));
        if(ranges!=null){for(Range<Integer> range:ranges){for(int fps:new int[]{range.getLower(),range.getUpper(),15,24,25,30,60}){if(fps>0&&fps<=60&&range.contains(fps)&&(duration==0||duration<=1_000_000_000L/fps+100_000)){result.add(fps);}}}}
        return new ArrayList<>(result);
    }
    JSONObject state()throws Exception{
        List<CameraCatalog.Lens> lenses=CameraCatalog.discover(manager);if(lenses.isEmpty()){throw new IllegalStateException("No camera exposed by Android");}
        CameraCatalog.Lens selected=service.captureLens()==null?lenses.get(0):service.captureLens();CameraCharacteristics c=selected.characteristics;CameraControls control=service.controls();
        JSONArray fields=new JSONArray(),options=new JSONArray();for(CameraCatalog.Lens lens:lenses){options.put(option(lens.key,lens.title));}
        fields.put(setting("lens","Camera / lens","Camera",selected.key,"choice",true).put("options",options));
        Range<Float> zoom=android.os.Build.VERSION.SDK_INT>=30?c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE):null;
        Float digital=c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);double low=selected.physicalId==null&&zoom!=null?zoom.getLower():1,high=selected.physicalId==null&&zoom!=null?zoom.getUpper():digital==null?1:digital;
        number(fields,"zoom","Zoom","Camera",control.zoom,low,Math.min(30,high),0.05,high>low);
        modes(fields,"focusMode","Focus mode","Camera",control.focusMode,c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),new String[]{"Manual / fixed","Auto / tap focus","Macro","Continuous video","Continuous picture","Extended depth"});
        Float focus=c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);number(fields,"focusDistance","Manual focus (diopters)","Camera",control.focusDistance,0,focus==null?0:focus,0.05,focus!=null&&focus>0);
        toggle(fields,"torch","Torch","Camera",control.torch,Boolean.TRUE.equals(manager.getCameraCharacteristics(selected.cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)));
        toggle(fields,"opticalStabilization","Optical stabilization","Camera",control.opticalStabilization,has(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),1));
        toggle(fields,"videoStabilization","Video stabilization","Camera",control.videoStabilization,has(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),1));
        boolean manual=has(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES),CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        toggle(fields,"autoExposure","Automatic exposure","Exposure",control.autoExposure,manual);
        toggle(fields,"exposureLock","Lock auto exposure","Exposure",control.exposureLock,Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)));
        Range<Integer> ev=c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);number(fields,"exposureCompensation","Exposure compensation (steps)","Exposure",control.exposureCompensation,ev==null?0:ev.getLower(),ev==null?0:ev.getUpper(),1,ev!=null&&ev.getUpper()>ev.getLower());
        Range<Integer> iso=c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);number(fields,"iso","ISO sensitivity","Exposure",control.iso,iso==null?100:iso.getLower(),iso==null?100:iso.getUpper(),1,manual&&iso!=null);
        Range<Long> shutter=c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);number(fields,"shutterNs","Shutter duration (ns)","Exposure",control.shutterNs,shutter==null?100000:shutter.getLower(),shutter==null?100000:Math.min(shutter.getUpper(),1_000_000_000L/service.captureFps()-100_000),100000,manual&&shutter!=null);
        modes(fields,"whiteBalance","White balance","Color",control.whiteBalance,c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),new String[]{"Manual / off","Auto","Incandescent","Fluorescent","Warm fluorescent","Daylight","Cloudy","Twilight","Shade"});
        toggle(fields,"whiteBalanceLock","Lock white balance","Color",control.whiteBalanceLock,Boolean.TRUE.equals(c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)));
        modes(fields,"noiseReduction","Noise reduction","Color",control.noiseReduction,c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),new String[]{"Off","Fast","High quality","Minimal","Zero shutter lag"});
        modes(fields,"edgeEnhancement","Sharpening","Color",control.edgeEnhancement,c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),new String[]{"Off","Fast","High quality","Zero shutter lag"});
        fields.put(setting("encoding","Video transport","Stream",service.rawMode()?"raw_nv12":"h264","choice",true).put("options",new JSONArray().put(option("h264","H.264 · USB or Wi-Fi")).put(option("raw_nv12","Uncompressed NV12 · USB 3 recommended"))));
        List<Size> sizes=sizes(c);Size size=service.captureSize();if(size==null&&!sizes.isEmpty()){size=sizes.get(sizes.size()-1);for(Size candidate:sizes){if(candidate.equals(new Size(1280,720))){size=candidate;break;}}}
        options=new JSONArray();for(Size candidate:sizes){options.put(option(candidate.toString(),candidate.getWidth()+" × "+candidate.getHeight()));}
        fields.put(setting("resolution","Resolution","Stream",size==null?"":size.toString(),"choice",!sizes.isEmpty()).put("options",options));
        options=new JSONArray();if(size!=null){for(int fps:rates(c,size)){options.put(option(fps,fps+" fps"));}}
        fields.put(setting("fps","Frame rate","Stream",service.captureFps(),"choice",options.length()>0).put("options",options));
        number(fields,"bitrate","Bitrate (Mbps)","Stream",service.captureBitrate(),2,60,1,true);
        return new JSONObject().put("version","0.5.0").put("device",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL).put("streaming",service.isStreaming()).put("status",service.status()).put("lens",selected.key).put("fields",fields);
    }
    JSONObject apply(JSONObject patch)throws Exception{
        if(!service.isStreaming()){throw new IllegalStateException("Start the camera on the unlocked phone first");}
        if(patch.has("encoding")){String encoding=patch.getString("encoding");if(!encoding.equals("raw_nv12")&&!encoding.equals("h264")){throw new IllegalArgumentException("Unknown transport");}service.setRawMode(encoding.equals("raw_nv12"));}
        List<CameraCatalog.Lens> lenses=CameraCatalog.discover(manager);CameraCatalog.Lens selected=service.captureLens();
        if(patch.has("lens")){selected=null;for(CameraCatalog.Lens lens:lenses){if(lens.key.equals(patch.getString("lens"))){selected=lens;break;}}if(selected==null){throw new IllegalArgumentException("Unknown camera route");}}
        if(patch.has("focusX")&&patch.has("focusY")){service.engine().focusAt((float)patch.getDouble("focusX"),(float)patch.getDouble("focusY"));}
        Size size=service.captureSize();List<Size> available=sizes(selected.characteristics);
        if(patch.has("resolution")){size=Size.parseSize(patch.getString("resolution"));}
        if(!available.contains(size)){size=null;for(Size candidate:available){if(candidate.equals(new Size(1280,720))){size=candidate;break;}}if(size==null&&!available.isEmpty()){size=available.get(available.size()-1);}}
        if(size==null){throw new IllegalArgumentException("No encoder surface size");}
        List<Integer> frameRates=rates(selected.characteristics,size);int fps=patch.optInt("fps",service.captureFps());
        if(!frameRates.contains(fps)){fps=frameRates.contains(30)?30:frameRates.isEmpty()?0:frameRates.get(0);}if(fps==0){throw new IllegalArgumentException("No supported frame rate");}
        CameraControls controls=service.controls();JSONArray definitions=state().getJSONArray("fields");
        for(int i=0;i<definitions.length();i++){
            JSONObject def=definitions.getJSONObject(i);String key=def.getString("key");if(!patch.has(key)||!def.getBoolean("enabled")||key.equals("lens")||key.equals("resolution")||key.equals("fps")||key.equals("bitrate")||key.equals("encoding")){continue;}
            Field field=CameraControls.class.getDeclaredField(key);String type=def.getString("type");
            if(type.equals("boolean")){field.setBoolean(controls,patch.getBoolean(key));}
            else if(type.equals("choice")){int value=patch.getInt(key);boolean allowed=false;JSONArray values=def.getJSONArray("options");for(int j=0;j<values.length();j++){if(values.getJSONObject(j).getInt("value")==value){allowed=true;}}if(!allowed){throw new IllegalArgumentException("Unavailable mode");}field.setInt(controls,value);}
            else{double value=patch.getDouble(key);if(!Double.isFinite(value)){throw new IllegalArgumentException("Invalid number");}value=Math.max(def.getDouble("min"),Math.min(def.getDouble("max"),value));if(field.getType()==float.class){field.setFloat(controls,(float)value);}else if(field.getType()==long.class){field.setLong(controls,(long)value);}else{field.setInt(controls,(int)value);}}
        }
        service.setControls(controls);service.updateCapture(selected,size,fps,Math.max(2,Math.min(60,patch.optInt("bitrate",service.captureBitrate()))),service.engine().activePreview());
        service.remoteChanged();return state();
    }
}
