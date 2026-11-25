#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <obs-module.h>
#include <util/platform.h>
#include <atomic>
#include <thread>
#include <vector>
#include <cstring>
#include <algorithm>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
}

struct Receiver {
    obs_source_t *source;
    int port;
    std::atomic<bool> stopping{false};
    std::atomic<SOCKET> socket{INVALID_SOCKET};
    std::thread worker;
    int64_t clockOffsetNs = 0;
    uint64_t clockRoundTripNs = 0;
    std::atomic<int> rotation{90};
};

static uint32_t read32(const uint8_t *bytes)
{
    return (uint32_t(bytes[0]) << 24) | (uint32_t(bytes[1]) << 16) | (uint32_t(bytes[2]) << 8) | bytes[3];
}
static uint64_t read64(const uint8_t *bytes)
{
    return (uint64_t(read32(bytes)) << 32) | read32(bytes + 4);
}

static bool readExact(Receiver *receiver, SOCKET socket, uint8_t *bytes, size_t length)
{
    while (length && !receiver->stopping.load()) {
        int received = recv(socket, reinterpret_cast<char *>(bytes), static_cast<int>(std::min(length, size_t(1 << 20))), 0);
        if (received <= 0) { return false; }
        bytes += received;
        length -= received;
    }
    return length == 0;
}

static void closeSocket(Receiver *receiver)
{
    SOCKET previous = receiver->socket.exchange(INVALID_SOCKET);
    if (previous != INVALID_SOCKET) { shutdown(previous, SD_BOTH); closesocket(previous); }
}

static SOCKET connectUsb(Receiver *receiver)
{
    SOCKET socket = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (socket == INVALID_SOCKET) { return INVALID_SOCKET; }
    receiver->socket.store(socket);
    u_long nonblocking = 1;
    ioctlsocket(socket, FIONBIO, &nonblocking);
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<u_short>(receiver->port));
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int result = connect(socket, reinterpret_cast<sockaddr *>(&address), sizeof(address));
    bool connected = result == 0;
    if (!connected && WSAGetLastError() == WSAEWOULDBLOCK) {
        for (int attempt = 0; attempt < 10 && !receiver->stopping.load(); ++attempt) {
            fd_set writable, errors;
            FD_ZERO(&writable); FD_ZERO(&errors); FD_SET(socket, &writable); FD_SET(socket, &errors);
            timeval timeout{0, 100000};
            if (select(0, nullptr, &writable, &errors, &timeout) > 0) {
                int error = 1, length = sizeof(error);
                getsockopt(socket, SOL_SOCKET, SO_ERROR, reinterpret_cast<char *>(&error), &length);
                connected = error == 0;
                break;
            }
        }
    }
    if (!connected || receiver->stopping.load()) { closeSocket(receiver); return INVALID_SOCKET; }
    nonblocking = 0;
    ioctlsocket(socket, FIONBIO, &nonblocking);
    BOOL noDelay = TRUE;
    setsockopt(socket, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char *>(&noDelay), sizeof(noDelay));
    DWORD receiveTimeout = 3000;
    setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<char *>(&receiveTimeout), sizeof(receiveTimeout));
    int bufferSize = 64 * 1024;
    setsockopt(socket, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<char *>(&bufferSize), sizeof(bufferSize));
    return socket;
}

static void receiveConnection(Receiver *receiver, SOCKET socket)
{
    uint64_t clockStarted = os_gettime_ns();
    const char ping[4] = {'D', 'A', 'P', 'G'};
    uint8_t phoneClock[8];
    if (send(socket, ping, 4, 0) != 4 || !readExact(receiver, socket, phoneClock, 8)) { return; }
    uint64_t clockFinished = os_gettime_ns();
    receiver->clockRoundTripNs = clockFinished - clockStarted;
    receiver->clockOffsetNs = static_cast<int64_t>((clockStarted + clockFinished) / 2) - static_cast<int64_t>(read64(phoneClock));
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    AVCodecContext *context = codec ? avcodec_alloc_context3(codec) : nullptr;
    AVFrame *decoded = av_frame_alloc();
    AVPacket *packet = av_packet_alloc();
    if (!context || !decoded || !packet) { goto cleanup; }
    context->thread_count = 1;
    context->thread_type = FF_THREAD_SLICE;
    context->flags |= AV_CODEC_FLAG_LOW_DELAY;
    if (avcodec_open2(context, codec, nullptr) < 0) { goto cleanup; }
    {
        std::vector<uint8_t> configuration;
        bool firstFrame = true;
        uint64_t decodeTotalNs = 0;
        uint64_t frameCount = 0;
        uint64_t captureTotalNs = 0;
        uint64_t captureCount = 0;
        while (!receiver->stopping.load()) {
            uint8_t header[20];
            if (!readExact(receiver, socket, header, sizeof(header))) { break; }
            uint32_t length = read32(header + 4), flags = read32(header + 16);
            if (read32(header) != 0x44414c54 || length == 0 || length > 32 * 1024 * 1024 || (flags & ~15u) != 0) {
                blog(LOG_WARNING, "[droid-alter] Invalid frame header; reconnecting. Update both APK and plugin together.");
                break;
            }
            size_t prefix = (flags & 1) ? configuration.size() : 0;
            if (av_new_packet(packet, static_cast<int>(length + prefix)) < 0) { break; }
            if (prefix) { std::memcpy(packet->data, configuration.data(), prefix); }
            if (!readExact(receiver, socket, packet->data + prefix, length)) { av_packet_unref(packet); break; }
            if (flags == 2) {
                int rotation = static_cast<int>(read64(header + 8));
                if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) { receiver->rotation.store(rotation); }
                configuration.assign(packet->data, packet->data + length);
                av_packet_unref(packet);
                continue;
            }
            if(flags&8){
                uint8_t *raw=packet->data;
                uint32_t w=length>=16?read32(raw):0,h=length>=16?read32(raw+4):0;
                if(w<2||h<2||w>4096||h>4096||((w|h)&1)||uint64_t(w)*h*3/2+16!=length){av_packet_unref(packet);break;}
                int angle=int(read32(raw+8));if(angle==0||angle==90||angle==180||angle==270){receiver->rotation.store(angle);}
                obs_source_frame frame{};frame.format=VIDEO_FORMAT_NV12;frame.width=w;frame.height=h;frame.data[0]=raw+16;frame.data[1]=raw+16+size_t(w)*h;frame.linesize[0]=frame.linesize[1]=w;frame.timestamp=os_gettime_ns();frame.full_range=false;
                video_format_get_parameters(VIDEO_CS_709,VIDEO_RANGE_PARTIAL,frame.color_matrix,frame.color_range_min,frame.color_range_max);obs_source_output_video(receiver->source,&frame);
                if(firstFrame){blog(LOG_INFO,"[droid-alter] Uncompressed USB NV12 %ux%u; no H.264 encoding/decoding",w,h);firstFrame=false;}
                av_packet_unref(packet);continue;
            }
            packet->pts = packet->dts = static_cast<int64_t>(read64(header + 8));
            if (flags & 1) { packet->flags |= AV_PKT_FLAG_KEY; }
            uint64_t started = os_gettime_ns();
            int sent = avcodec_send_packet(context, packet);
            av_packet_unref(packet);
            if (sent < 0) { continue; }
            while (avcodec_receive_frame(context, decoded) == 0) {
                obs_source_frame frame{};
                if (decoded->format == AV_PIX_FMT_YUV420P || decoded->format == AV_PIX_FMT_YUVJ420P) {
                    frame.format = VIDEO_FORMAT_I420;
                } else if (decoded->format == AV_PIX_FMT_NV12) {
                    frame.format = VIDEO_FORMAT_NV12;
                } else {
                    blog(LOG_WARNING, "[droid-alter] Unexpected AVC pixel format %d", decoded->format);
                    av_frame_unref(decoded);
                    continue;
                }
                for (int plane = 0; plane < 3; ++plane) {
                    frame.data[plane] = decoded->data[plane];
                    frame.linesize[plane] = static_cast<uint32_t>(decoded->linesize[plane]);
                }
                frame.width = decoded->width;
                frame.height = decoded->height;
                frame.timestamp = os_gettime_ns();
                if (flags & 4) {
                    int64_t captured = static_cast<int64_t>(read64(header + 8)) * 1000 + receiver->clockOffsetNs;
                    int64_t age = static_cast<int64_t>(frame.timestamp) - captured;
                    if (age >= 0 && age < 2000000000LL) { captureTotalNs += age; captureCount++; }
                }
                frame.full_range = decoded->color_range == AVCOL_RANGE_JPEG;
                video_format_get_parameters(VIDEO_CS_709, frame.full_range ? VIDEO_RANGE_FULL : VIDEO_RANGE_PARTIAL,
                    frame.color_matrix, frame.color_range_min, frame.color_range_max);
                obs_source_output_video(receiver->source, &frame);
                decodeTotalNs += os_gettime_ns() - started;
                frameCount++;
                if (firstFrame || frameCount % 300 == 0) {
                    blog(LOG_INFO, "[droid-alter] Direct video %ux%u; single-thread decode + delivery %.2f ms; no playback queue",
                         frame.width, frame.height, double(decodeTotalNs) / double(frameCount) / 1000000.0);
                    if (captureCount) {
                        blog(LOG_INFO, "[droid-alter] Measured sensor capture to OBS delivery %.1f ms; USB clock-sync uncertainty +/- %.1f ms (excludes screen refresh)",
                            double(captureTotalNs) / double(captureCount) / 1000000.0, double(receiver->clockRoundTripNs) / 2000000.0);
                    }
                    firstFrame = false;
                }
                av_frame_unref(decoded);
            }
        }
    }
cleanup:
    av_packet_free(&packet);
    av_frame_free(&decoded);
    avcodec_free_context(&context);
}

static void receiverState(void *data, calldata_t *arguments)
{
    auto *receiver = static_cast<Receiver *>(data);
    calldata_set_int(arguments, "rotation", receiver->rotation.load());
}

static void *receiverCreate(obs_data_t *settings, obs_source_t *source)
{
    WSADATA winsock{};
    if (WSAStartup(MAKEWORD(2, 2), &winsock) != 0) { return nullptr; }
    auto *receiver = new Receiver;
    receiver->source = source;
    receiver->port = static_cast<int>(obs_data_get_int(settings, "port"));
    obs_source_set_async_unbuffered(source, true);
    proc_handler_add(obs_source_get_proc_handler(source), "void get_camera_state(out int rotation)", receiverState, receiver);
    receiver->worker = std::thread([receiver]() {
        while (!receiver->stopping.load()) {
            SOCKET socket = connectUsb(receiver);
            if (socket != INVALID_SOCKET) { receiveConnection(receiver, socket); }
            closeSocket(receiver);
            for (int attempt = 0; attempt < 5 && !receiver->stopping.load(); ++attempt) { os_sleep_ms(50); }
        }
    });
    return receiver;
}

static void receiverDestroy(void *data)
{
    auto *receiver = static_cast<Receiver *>(data);
    receiver->stopping.store(true);
    closeSocket(receiver);
    if (receiver->worker.joinable()) { receiver->worker.join(); }
    delete receiver;
    WSACleanup();
}

static const char *receiverName(void *) { return "CamBridge direct decoder (internal)"; }
void registerDirectDecoder()
{
    obs_source_info info{};
    info.id = "droid_alter_direct_decoder";
    info.type = OBS_SOURCE_TYPE_INPUT;
    info.output_flags = OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_CAP_DISABLED;
    info.get_name = receiverName;
    info.create = receiverCreate;
    info.destroy = receiverDestroy;
    obs_register_source(&info);
}
