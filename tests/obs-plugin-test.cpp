#include <obs.h>
#include <cstdio>
#include <cstdlib>

static void require(bool passed, const char *message)
{
    if (!passed) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
    std::printf("PASS: %s\n", message);
}

int main(int argc, char **argv)
{
    require(argc == 3, "plugin binary and data paths supplied");
    require(obs_startup("en-US", nullptr, nullptr), "libobs 33 startup");
    obs_module_t *module = nullptr;
    require(obs_open_module(&module, argv[1], argv[2]) == MODULE_SUCCESS, "native DLL loads against installed OBS");
    require(obs_init_module(module), "OBS source registration");
    require(obs_source_get_display_name("droid_alter_usb_camera") != nullptr, "CamBridge source discoverable");
    obs_data_t *settings = obs_data_create();
    obs_data_set_bool(settings, "enabled", false);
    obs_source_t *source = obs_source_create("droid_alter_usb_camera", "USB plugin test", settings, nullptr);
    require(source != nullptr, "source construction without a connected phone");
    require(obs_source_get_width(source) == 1080 && obs_source_get_height(source) == 1920, "default portrait dimensions");
    obs_data_set_int(settings, "rotation", 0);
    obs_source_t *landscape = obs_source_create("droid_alter_usb_camera", "Landscape test", settings, nullptr);
    require(landscape && obs_source_get_width(landscape) == 1920 && obs_source_get_height(landscape) == 1080, "landscape dimensions");
    obs_source_release(landscape);
    obs_properties_t *properties = obs_source_properties(source);
    require(properties && obs_properties_get(properties, "reconnect"), "USB reconnect control");
    require(obs_properties_get(properties, "rotation") && obs_properties_get(properties, "adb_path"), "camera connection properties");
    obs_properties_destroy(properties);
    obs_source_release(source);
    obs_data_release(settings);
    obs_shutdown();
    std::puts("All OBS plugin integration checks passed.");
    return 0;
}
