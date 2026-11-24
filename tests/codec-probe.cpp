extern "C" {
#include <libavcodec/avcodec.h>
}
#include <cstdio>
#include <initializer_list>
int main() {
    for (const char *name : {"libx264", "h264_nvenc", "h264_mf"}) {
        std::printf("%s: %s\n", name, avcodec_find_encoder_by_name(name) ? "available" : "unavailable");
    }
    return 0;
}
