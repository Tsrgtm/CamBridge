#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <obs-module.h>
#include <graphics/graphics.h>
#include <util/platform.h>
#include <mutex>
#include <string>
#include <vector>
#include <cmath>
#include <atomic>
#include <thread>

OBS_DECLARE_MODULE()
MODULE_EXPORT const char *obs_module_description(void) { return "CamBridge: free USB Android camera with native OBS integration"; }
MODULE_EXPORT const char *obs_module_name(void) { return "CamBridge USB Camera"; }
MODULE_EXPORT const char *obs_module_author(void) { return "CamBridge contributors"; }

struct UsbCamera {
    obs_source_t *source = nullptr;
    obs_source_t *decoder = nullptr;
    std::mutex mutex;
    std::atomic<bool> cancelled{false};
    std::thread connector;
    int rotation = 90;
    bool mirror = false;
    bool nativeSize = false;
    std::string status = "Start CamBridge on the phone, then connect.";
};

static std::wstring wide(const std::string &text)
{
    int length = MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, nullptr, 0);
    if (length < 1) { return L""; }
    std::wstring output(length, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.c_str(), -1, output.data(), length);
    output.resize(length - 1);
    return output;
}

static bool validSerial(const std::string &serial)
{
    for (unsigned char character : serial) {
        if (!((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
              (character >= '0' && character <= '9') || character == '-' || character == '_' || character == '.')) {
            return false;
        }
    }
    return true;
}

static bool forwardUsb(const std::string &adb, const std::string &serial, int port, std::string &error,
                       const std::atomic<bool> &cancelled)
{
    if (!validSerial(serial)) { error = "USB serial must contain letters, numbers, hyphens, underscores or periods."; return false; }
    if (adb.find('"') != std::string::npos || GetFileAttributesW(wide(adb).c_str()) == INVALID_FILE_ATTRIBUTES) {
        error = "ADB was not found. Select platform-tools/adb.exe in this source's settings.";
        return false;
    }
    std::wstring arguments = L"\"" + wide(adb) + L"\" " + (serial.empty() ? L"-d" : L"-s " + wide(serial)) +
        L" forward tcp:" + std::to_wstring(port) + L" tcp:5889";
    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESHOWWINDOW;
    startup.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION process{};
    if (!CreateProcessW(wide(adb).c_str(), arguments.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW,
                        nullptr, nullptr, &startup, &process)) {
        error = "Could not run ADB (Windows error " + std::to_string(GetLastError()) + ").";
        return false;
    }
    DWORD wait = WAIT_TIMEOUT;
    for (int attempt = 0; attempt < 100 && !cancelled.load(); ++attempt) {
        wait = WaitForSingleObject(process.hProcess, 50);
        if (wait != WAIT_TIMEOUT) { break; }
    }
    DWORD exitCode = 1;
    if (wait == WAIT_OBJECT_0) { GetExitCodeProcess(process.hProcess, &exitCode); }
    else { TerminateProcess(process.hProcess, 1); }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    if (exitCode != 0) {
        error = "USB connection failed. Unlock and authorize the phone, enable USB debugging, and connect its data cable. With multiple phones, enter the USB serial.";
        return false;
    }
    return true;
}

static obs_source_t *decoderReference(UsbCamera *camera)
{
    std::lock_guard<std::mutex> lock(camera->mutex);
    return camera->decoder ? obs_source_get_ref(camera->decoder) : nullptr;
}

static void disconnectCamera(UsbCamera *camera)
{
    camera->cancelled.store(true);
    if (camera->connector.joinable()) { camera->connector.join(); }
    obs_source_t *old;
    {
        std::lock_guard<std::mutex> lock(camera->mutex);
        old = camera->decoder;
        camera->decoder = nullptr;
    }
    if (old) {
        obs_source_remove_active_child(camera->source, old);
        obs_source_release(old);
    }
}

static void cameraUpdate(void *data, obs_data_t *settings)
{
    auto *camera = static_cast<UsbCamera *>(data);
    disconnectCamera(camera);
    {
        std::lock_guard<std::mutex> lock(camera->mutex);
        int rotation = static_cast<int>(obs_data_get_int(settings, "rotation"));
        camera->rotation = (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) ? rotation : -1;
        camera->mirror = obs_data_get_bool(settings, "mirror");
        camera->nativeSize = obs_data_get_bool(settings, "native_size");
        camera->status = "Disconnected.";
    }
    if (!obs_data_get_bool(settings, "enabled")) { return; }
    int port = static_cast<int>(obs_data_get_int(settings, "port"));
    if (port < 1024 || port > 65535) { port = 5889; }
    std::string adb = obs_data_get_string(settings, "adb_path");
    std::string serial = obs_data_get_string(settings, "serial");
    camera->cancelled.store(false);
    {
        std::lock_guard<std::mutex> lock(camera->mutex);
        camera->status = "Connecting USB…";
    }
    camera->connector = std::thread([camera, adb, serial, port]() {
    std::string error;
    if (!forwardUsb(adb, serial, port, error, camera->cancelled)) {
        if (camera->cancelled.load()) { return; }
        {
        std::lock_guard<std::mutex> lock(camera->mutex);
        camera->status = error;
        }
        blog(LOG_WARNING, "[droid-alter] %s", error.c_str());
        obs_source_update_properties(camera->source);
        return;
    }
    if (camera->cancelled.load()) { return; }
    obs_data_t *media = obs_data_create();
    obs_data_set_int(media, "port", port);
    obs_source_t *decoder = obs_source_create_private("droid_alter_direct_decoder", "CamBridge direct receiver", media);
    obs_data_release(media);
    if (!decoder) {
        std::lock_guard<std::mutex> lock(camera->mutex);
        camera->status = "Direct decoder could not be created. Reinstall the matching APK and plugin.";
        return;
    }
    if (!obs_source_add_active_child(camera->source, decoder)) {
        obs_source_release(decoder);
        return;
    }
    if (camera->cancelled.load()) {
        obs_source_remove_active_child(camera->source, decoder);
        obs_source_release(decoder);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(camera->mutex);
        camera->decoder = decoder;
        camera->status = "USB forwarded. Start the phone camera to receive video. Video reconnects automatically.";
    }
    blog(LOG_INFO, "[droid-alter] USB video receiver on local port %d", port);
    obs_source_update_properties(camera->source);
    });
}

static const char *cameraName(void *) { return "CamBridge USB Camera"; }
static void *cameraCreate(obs_data_t *settings, obs_source_t *source)
{
    auto *camera = new UsbCamera;
    camera->source = source;
    cameraUpdate(camera, settings);
    return camera;
}
static void cameraDestroy(void *data)
{
    auto *camera = static_cast<UsbCamera *>(data);
    disconnectCamera(camera);
    delete camera;
}
static void cameraDefaults(obs_data_t *settings)
{
    char *path = obs_module_file("adb/adb.exe");
    obs_data_set_default_string(settings, "adb_path", path ? path : "");
    bfree(path);
    obs_data_set_default_bool(settings, "enabled", true);
    obs_data_set_default_int(settings, "port", 5889);
    obs_data_set_default_int(settings, "rotation", -1);
}

static bool reconnectClicked(obs_properties_t *, obs_property_t *, void *data)
{
    auto *camera = static_cast<UsbCamera *>(data);
    obs_data_t *settings = obs_source_get_settings(camera->source);
    obs_data_set_bool(settings, "enabled", true);
    obs_source_update(camera->source, settings);
    obs_data_release(settings);
    return true;
}

static obs_properties_t *cameraProperties(void *data)
{
    auto *camera = static_cast<UsbCamera *>(data);
    obs_properties_t *properties = obs_properties_create();
    obs_properties_set_flags(properties, OBS_PROPERTIES_DEFER_UPDATE);
    std::string status = "Start CamBridge on your phone. Connect a USB data cable and authorize this PC.";
    if (camera) { std::lock_guard<std::mutex> lock(camera->mutex); status = camera->status; }
    obs_properties_add_text(properties, "status", status.c_str(), OBS_TEXT_INFO);
    obs_properties_add_bool(properties, "enabled", "Connect camera");
    obs_properties_add_text(properties, "serial", "USB serial (blank = the only connected USB phone)", OBS_TEXT_DEFAULT);
    obs_properties_add_path(properties, "adb_path", "Android platform-tools / adb.exe", OBS_PATH_FILE, "ADB executable (*.exe)", nullptr);
    obs_properties_add_int(properties, "port", "PC USB port (use a unique port for each phone)", 1024, 65535, 1);
    obs_property_t *rotation = obs_properties_add_list(properties, "rotation", "Orientation", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(rotation, "Automatic · follow selected phone camera", -1);
    obs_property_list_add_int(rotation, "Landscape · 0°", 0);
    obs_property_list_add_int(rotation, "Portrait · 90° clockwise (usual rear camera)", 90);
    obs_property_list_add_int(rotation, "Landscape · 180°", 180);
    obs_property_list_add_int(rotation, "Portrait · 270° clockwise (usual front camera)", 270);
    obs_properties_add_bool(properties, "mirror", "Mirror horizontally");
    obs_properties_add_bool(properties, "native_size", "Use native camera resolution (otherwise keep a stable Full HD source)");
    obs_properties_add_text(properties, "help", "Direct low-latency H.264 decoder · no playback queue · automatic frame rate. Resolution, bitrate, lens, zoom, exposure and torch are controlled on the phone. Start the phone camera before locking the screen. Add your microphone separately in OBS.", OBS_TEXT_INFO);
    obs_properties_add_button2(properties, "reconnect", "Reconnect USB", reconnectClicked, camera);
    return properties;
}

static int rotationFor(int configured, obs_source_t *decoder)
{
    if (configured != -1 || !decoder) { return configured == -1 ? 90 : configured; }
    calldata_t arguments;
    calldata_init(&arguments);
    bool found = proc_handler_call(obs_source_get_proc_handler(decoder), "get_camera_state", &arguments);
    int rotation = found ? static_cast<int>(calldata_int(&arguments, "rotation")) : 90;
    calldata_free(&arguments);
    return rotation;
}

static uint32_t dimension(void *data, bool width)
{
    auto *camera = static_cast<UsbCamera *>(data);
    std::lock_guard<std::mutex> lock(camera->mutex);
    int rotation = rotationFor(camera->rotation, camera->decoder);
    bool portrait = rotation == 90 || rotation == 270;
    bool sourceWidth = width != portrait;
    if (!camera->nativeSize) { return sourceWidth ? 1920 : 1080; }
    if (!camera->decoder) { return sourceWidth ? 1920 : 1080; }
    uint32_t value = sourceWidth ? obs_source_get_width(camera->decoder) : obs_source_get_height(camera->decoder);
    return value ? value : (sourceWidth ? 1920 : 1080);
}
static uint32_t cameraWidth(void *data) { return dimension(data, true); }
static uint32_t cameraHeight(void *data) { return dimension(data, false); }

static void cameraRender(void *data, gs_effect_t *)
{
    auto *camera = static_cast<UsbCamera *>(data);
    obs_source_t *decoder = decoderReference(camera);
    if (!decoder) { return; }
    uint32_t width = obs_source_get_width(decoder), height = obs_source_get_height(decoder);
    int rotation;
    bool mirror;
    { std::lock_guard<std::mutex> lock(camera->mutex); rotation = rotationFor(camera->rotation, decoder); mirror = camera->mirror; }
    gs_matrix_push();
    float rotatedWidth = static_cast<float>((rotation == 90 || rotation == 270) ? height : width);
    float rotatedHeight = static_cast<float>((rotation == 90 || rotation == 270) ? width : height);
    if (rotatedWidth > 0 && rotatedHeight > 0) {
        float outputWidth = static_cast<float>(cameraWidth(data)), outputHeight = static_cast<float>(cameraHeight(data));
        float scale = std::min(outputWidth / rotatedWidth, outputHeight / rotatedHeight);
        gs_matrix_translate3f((outputWidth - rotatedWidth * scale) / 2, (outputHeight - rotatedHeight * scale) / 2, 0);
        gs_matrix_scale3f(scale, scale, 1);
    }
    if (mirror) {
        gs_matrix_translate3f(static_cast<float>((rotation == 90 || rotation == 270) ? height : width), 0, 0);
        gs_matrix_scale3f(-1, 1, 1);
    }
    if (rotation == 90) { gs_matrix_translate3f(static_cast<float>(height), 0, 0); }
    else if (rotation == 180) { gs_matrix_translate3f(static_cast<float>(width), static_cast<float>(height), 0); }
    else if (rotation == 270) { gs_matrix_translate3f(0, static_cast<float>(width), 0); }
    gs_matrix_rotaa4f(0, 0, 1, static_cast<float>(rotation * 3.141592653589793 / 180.0));
    obs_source_video_render(decoder);
    gs_matrix_pop();
    obs_source_release(decoder);
}

static void enumChildren(void *data, obs_source_enum_proc_t callback, void *parameter)
{
    auto *camera = static_cast<UsbCamera *>(data);
    obs_source_t *decoder = decoderReference(camera);
    if (decoder) { callback(camera->source, decoder, parameter); obs_source_release(decoder); }
}

static bool cameraAudio(void *, uint64_t *, obs_source_audio_mix *, uint32_t, size_t, size_t)
{
    return false;
}

void registerDirectDecoder();
bool obs_module_load(void)
{
    registerDirectDecoder();
    obs_source_info information{};
    information.id = "droid_alter_usb_camera";
    information.type = OBS_SOURCE_TYPE_INPUT;
    information.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_COMPOSITE;
    information.get_name = cameraName;
    information.create = cameraCreate;
    information.destroy = cameraDestroy;
    information.update = cameraUpdate;
    information.get_defaults = cameraDefaults;
    information.get_properties = cameraProperties;
    information.get_width = cameraWidth;
    information.get_height = cameraHeight;
    information.video_render = cameraRender;
    information.enum_active_sources = enumChildren;
    information.enum_all_sources = enumChildren;
    information.audio_render = cameraAudio;
    information.icon_type = OBS_ICON_TYPE_CAMERA;
    obs_register_source(&information);
    blog(LOG_INFO, "[droid-alter] USB camera plugin 0.5.0 loaded · direct low-latency decode and automatic orientation");
    return true;
}
