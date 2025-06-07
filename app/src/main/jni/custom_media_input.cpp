#include <jni.h>
#include <vlc/vlc.h>
#include <android/log.h>
#include <vector>
#include <mutex>
#include <algorithm> // Required for std::min

#define TAG "CustomMediaInputJNI"

// Structure to hold data passed to callbacks
typedef struct {
    JavaVM *jvm;
    jobject nal_queue_obj; // Global ref to ArrayBlockingQueue<byte[]>
    jmethodID nal_queue_poll_method_id;
    // jmethodID nal_queue_peek_method_id; // For checking if casting is stopping (optional)
    jbyteArray sps_pps_jbyteArray; // Global ref to sps_pps_data byte array, held until open_cb
    std::vector<uint8_t> sps_pps_vector; // Parsed SPS/PPS data
    size_t sps_pps_sent_offset; // To track how much of sps_pps_vector has been sent
    bool sps_pps_fully_sent;
    bool stream_opened;
    libvlc_instance_t *vlc_instance;
    jobject time_unit_milliseconds_obj; // Global ref for TimeUnit.MILLISECONDS
} media_input_opaque_t;

// Helper to get JNIEnv
JNIEnv* get_jni_env(JavaVM *jvm) {
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to attach current thread");
            return nullptr;
        }
    } else if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get JNI environment");
        return nullptr;
    }
    return env;
}

// Helper function to get native pointer from VLCObject subclasses
static jlong get_native_pointer(JNIEnv* env, jobject obj, const char* defaultFieldName = "mNativeAddr") {
    if (!obj) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "get_native_pointer: Java object is null");
        return 0;
    }
    jclass clazz = env->GetObjectClass(obj);
    if (!clazz) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "get_native_pointer: Failed to get object class");
        return 0;
    }

    jfieldID fieldId = env->GetFieldID(clazz, defaultFieldName, "J"); // "J" for long
    if (fieldId == nullptr) {
        // Clear exception from GetFieldID failing (if any)
        env->ExceptionClear();
        // Try alternative common field name "nativeReference"
        fieldId = env->GetFieldID(clazz, "nativeReference", "J");
        if (fieldId == nullptr) {
            env->ExceptionClear();
            __android_log_print(ANDROID_LOG_ERROR, TAG, "get_native_pointer: Could not find field '%s' or 'nativeReference' (J) in class", defaultFieldName);
            env->DeleteLocalRef(clazz);
            return 0;
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "get_native_pointer: Found field 'nativeReference'");
    } else {
        // __android_log_print(ANDROID_LOG_INFO, TAG, "get_native_pointer: Found field '%s'", defaultFieldName);
    }

    jlong nativePtr = env->GetLongField(obj, fieldId);
    env->DeleteLocalRef(clazz);
    return nativePtr;
}

// LibVLC media callbacks
static int open_cb(void *opaque, void **datap, uint64_t *sizep) {
    auto *data = static_cast<media_input_opaque_t *>(opaque);
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "open_cb: opaque data is null");
        return -1;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "open_cb called");

    JNIEnv *env = get_jni_env(data->jvm);
    if (!env) return -1;

    // Parse SPS/PPS data if available (jbyteArray was stored in opaque struct)
    if (data->sps_pps_jbyteArray) {
        jbyte* sps_pps_bytes = env->GetByteArrayElements(data->sps_pps_jbyteArray, nullptr);
        if (sps_pps_bytes) {
            jsize len = env->GetArrayLength(data->sps_pps_jbyteArray);
            data->sps_pps_vector.assign(sps_pps_bytes, sps_pps_bytes + len);
            env->ReleaseByteArrayElements(data->sps_pps_jbyteArray, sps_pps_bytes, JNI_ABORT);
            __android_log_print(ANDROID_LOG_INFO, TAG, "open_cb: Copied SPS/PPS data to native vector, size: %d", (int)len);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "open_cb: Failed to get sps_pps_jbyteArray elements");
        }
        // Release the global ref for the jbyteArray now that it's copied or processing failed
        env->DeleteGlobalRef(data->sps_pps_jbyteArray);
        data->sps_pps_jbyteArray = nullptr;
    }


    data->sps_pps_fully_sent = data->sps_pps_vector.empty();
    data->sps_pps_sent_offset = 0;
    data->stream_opened = true;
    *datap = opaque;
    *sizep = UINT64_MAX;
    return 0;
}

static ssize_t read_cb(void *opaque, unsigned char *buf, size_t len) {
    auto *data = static_cast<media_input_opaque_t *>(opaque);
    if (!data || !data->stream_opened) {
        // __android_log_print(ANDROID_LOG_VERBOSE, TAG, "read_cb: Opaque data is null or stream not opened");
        return -1;
    }

    JNIEnv *env = get_jni_env(data->jvm);
    if (!env) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "read_cb: Failed to get JNIEnv");
        return -1;
    }

    // Send SPS/PPS first if available and not yet fully sent
    if (!data->sps_pps_fully_sent && !data->sps_pps_vector.empty()) {
        size_t remaining_sps_pps = data->sps_pps_vector.size() - data->sps_pps_sent_offset;
        size_t to_copy = std::min(len, remaining_sps_pps);

        memcpy(buf, data->sps_pps_vector.data() + data->sps_pps_sent_offset, to_copy);
        data->sps_pps_sent_offset += to_copy;

        if (data->sps_pps_sent_offset >= data->sps_pps_vector.size()) {
            data->sps_pps_fully_sent = true;
            __android_log_print(ANDROID_LOG_INFO, TAG, "read_cb: Fully sent SPS/PPS data, total %zu bytes", data->sps_pps_vector.size());
        } else {
            __android_log_print(ANDROID_LOG_INFO, TAG, "read_cb: Partially sent SPS/PPS data, %zu bytes this call", to_copy);
        }
        return to_copy;
    }

    if (!data->time_unit_milliseconds_obj) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "read_cb: TimeUnit.MILLISECONDS global ref is null!");
        return -1;
    }

    jobject nal_jbyte_array_obj = env->CallObjectMethod(data->nal_queue_obj, data->nal_queue_poll_method_id, 100L, data->time_unit_milliseconds_obj);

    if (env->ExceptionCheck()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "read_cb: Exception when polling from queue");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -1;
    }

    if (nal_jbyte_array_obj == nullptr) {
        // __android_log_print(ANDROID_LOG_VERBOSE, TAG, "read_cb: nal_queue_poll_method_id returned null. Potential end of stream or timeout.");
        return 0;
    }

    jbyteArray nal_byte_array = static_cast<jbyteArray>(nal_jbyte_array_obj);
    jbyte *nal_bytes = env->GetByteArrayElements(nal_byte_array, nullptr);
    if (nal_bytes == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "read_cb: Failed to get byte array elements");
        env->DeleteLocalRef(nal_byte_array); // was nal_jbyte_array_obj
        return -1;
    }

    jsize nal_len = env->GetArrayLength(nal_byte_array);
    size_t to_copy = std::min(len, (size_t)nal_len);
    memcpy(buf, nal_bytes, to_copy);

    env->ReleaseByteArrayElements(nal_byte_array, nal_bytes, JNI_ABORT);
    env->DeleteLocalRef(nal_byte_array); // was nal_jbyte_array_obj

    // __android_log_print(ANDROID_LOG_VERBOSE, TAG, "read_cb: Sent %zu NAL bytes", to_copy);
    return to_copy;
}

static int seek_cb(void *opaque, uint64_t offset) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "seek_cb called, but seeking is not supported for live stream.");
    return -1; // Not seekable
}

static void close_cb(void *opaque) {
    auto *data = static_cast<media_input_opaque_t *>(opaque);
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "close_cb: opaque data is null");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "close_cb called");

    JNIEnv *env = get_jni_env(data->jvm);
    if (env) {
        if (data->nal_queue_obj) {
            env->DeleteGlobalRef(data->nal_queue_obj);
            data->nal_queue_obj = nullptr;
        }
        if (data->sps_pps_jbyteArray) { // Should be null if open_cb was successful
            env->DeleteGlobalRef(data->sps_pps_jbyteArray);
            data->sps_pps_jbyteArray = nullptr;
        }
        if (data->time_unit_milliseconds_obj) { // Release TimeUnit global ref
            env->DeleteGlobalRef(data->time_unit_milliseconds_obj);
            data->time_unit_milliseconds_obj = nullptr;
        }
    } else {
         __android_log_print(ANDROID_LOG_ERROR, TAG, "close_cb: Failed to get JNIEnv for cleanup.");
    }
    data->sps_pps_vector.clear();
    data->stream_opened = false;

    delete data; // Free the structure itself
    __android_log_print(ANDROID_LOG_INFO, TAG, "close_cb: Freed opaque data structure.");
}

static JavaVM* g_jvm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "JNI_OnLoad called");
    g_jvm = vm;
    // TimeUnit.MILLISECONDS global ref will be created per media instance now,
    // to simplify JNI_OnLoad and avoid issues if ScreenCastingService is reloaded.
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_home_screen_1to_1chromecast_casting_ScreenCastingService_nativeSetupCustomMediaAndPlay(
        JNIEnv *env,
        jobject thiz, // Instance of ScreenCastingService
        jobject mediaPlayerJObject,
        jobject libVLCJObject,
        jobject nalQueueJObject,
        jbyteArray spsPpsDataArray,
        jobject rendererItemJObject) {

    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeSetupCustomMediaAndPlay called");

    if (!g_jvm) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSetupCustomMediaAndPlay: g_jvm is null!");
        return JNI_FALSE;
    }
    if (!mediaPlayerJObject || !libVLCJObject || !nalQueueJObject || !rendererItemJObject) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSetupCustomMediaAndPlay: One or more jobject parameters are null!");
        return JNI_FALSE;
    }

    libvlc_instance_t* nativeLibVLCInstance = (libvlc_instance_t*) get_native_pointer(env, libVLCJObject);
    libvlc_media_player_t* nativeMediaPlayer = (libvlc_media_player_t*) get_native_pointer(env, mediaPlayerJObject);
    libvlc_renderer_item_t* nativeRendererItem = (libvlc_renderer_item_t*) get_native_pointer(env, rendererItemJObject);

    if (!nativeLibVLCInstance || !nativeMediaPlayer || !nativeRendererItem) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSetupCustomMediaAndPlay: Failed to get one or more native pointers via reflection.");
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeSetupCustomMediaAndPlay: Native pointers: VLCInst=%p, MP=%p, Renderer=%p",
        nativeLibVLCInstance, nativeMediaPlayer, nativeRendererItem);


    // Setup opaque data for callbacks
    auto *data = new(std::nothrow) media_input_opaque_t();
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSetupCustomMediaAndPlay: Failed to allocate opaque data struct");
        return JNI_FALSE;
    }
    data->jvm = g_jvm;
    data->nal_queue_obj = nullptr; // Initialize before potential failure paths
    data->sps_pps_jbyteArray = nullptr;
    data->time_unit_milliseconds_obj = nullptr;

    data->nal_queue_obj = env->NewGlobalRef(nalQueueJObject);
     if (!data->nal_queue_obj) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSetupCustomMediaAndPlay: Failed to create global ref for nalQueueJObject");
        delete data;
        return JNI_FALSE;
    }

    if (spsPpsDataArray) {
        data->sps_pps_jbyteArray = static_cast<jbyteArray>(env->NewGlobalRef(spsPpsDataArray));
         if (!data->sps_pps_jbyteArray) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "nativeSetupCustomMediaAndPlay: Failed to create global ref for spsPpsDataArray, proceeding without it.");
            // Not a fatal error, SPS/PPS might come later or not at all
        }
    }
    data->sps_pps_fully_sent = false;
    data->sps_pps_sent_offset = 0;
    data->stream_opened = false;
    data->vlc_instance = nativeLibVLCInstance;
    // data->time_unit_milliseconds_obj = nullptr; // Already initialized

    // Get TimeUnit.MILLISECONDS enum value
    jclass timeUnitClass = env->FindClass("java/util/concurrent/TimeUnit");
    if (timeUnitClass) {
        jfieldID millisecondsFieldId = env->GetStaticFieldID(timeUnitClass, "MILLISECONDS", "Ljava/util/concurrent/TimeUnit;");
        if (millisecondsFieldId) {
            jobject localTimeUnitMs = env->GetStaticObjectField(timeUnitClass, millisecondsFieldId);
            if (localTimeUnitMs) {
                data->time_unit_milliseconds_obj = env->NewGlobalRef(localTimeUnitMs);
                env->DeleteLocalRef(localTimeUnitMs);
            }
        }
        env->DeleteLocalRef(timeUnitClass);
    }
    if (!data->time_unit_milliseconds_obj) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create global ref for TimeUnit.MILLISECONDS for instance.");
        // Cleanup data
        if(data->nal_queue_obj) env->DeleteGlobalRef(data->nal_queue_obj);
        if(data->sps_pps_jbyteArray) env->DeleteGlobalRef(data->sps_pps_jbyteArray);
        delete data;
        return JNI_FALSE;
    }

    jclass queue_clazz = env->GetObjectClass(data->nal_queue_obj);
     if (!queue_clazz) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get ObjectClass for nalQueueJObject.");
        if(data->nal_queue_obj) env->DeleteGlobalRef(data->nal_queue_obj);
        if(data->sps_pps_jbyteArray) env->DeleteGlobalRef(data->sps_pps_jbyteArray);
        if(data->time_unit_milliseconds_obj) env->DeleteGlobalRef(data->time_unit_milliseconds_obj);
        delete data;
        return JNI_FALSE;
    }
    data->nal_queue_poll_method_id = env->GetMethodID(queue_clazz, "poll", "(JLjava/util/concurrent/TimeUnit;)Ljava/lang/Object;");
    env->DeleteLocalRef(queue_clazz);

    if (!data->nal_queue_poll_method_id) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get poll method ID.");
        // Cleanup data
        if(data->nal_queue_obj) env->DeleteGlobalRef(data->nal_queue_obj);
        if(data->sps_pps_jbyteArray) env->DeleteGlobalRef(data->sps_pps_jbyteArray);
        if(data->time_unit_milliseconds_obj) env->DeleteGlobalRef(data->time_unit_milliseconds_obj);
        delete data;
        return JNI_FALSE;
    }

    // Create custom media
    libvlc_media_t *custom_media = libvlc_media_new_callbacks(
            nativeLibVLCInstance,
            open_cb, read_cb, seek_cb, close_cb, data);

    if (!custom_media) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "libvlc_media_new_callbacks failed");
        // Cleanup data as close_cb won't be called by VLC
        if(data->nal_queue_obj) env->DeleteGlobalRef(data->nal_queue_obj);
        if(data->sps_pps_jbyteArray) env->DeleteGlobalRef(data->sps_pps_jbyteArray);
        if(data->time_unit_milliseconds_obj) env->DeleteGlobalRef(data->time_unit_milliseconds_obj);
        delete data;
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Custom media created via callbacks: %p", custom_media);

    // Add media options
    libvlc_media_add_option(custom_media, ":demux=h264");
    libvlc_media_add_option(custom_media, ":h264-fps=30"); // Assuming 30fps
    __android_log_print(ANDROID_LOG_INFO, TAG, "Added media options to custom media.");

    // Set media to player
    libvlc_media_player_set_media(nativeMediaPlayer, custom_media);
    libvlc_media_release(custom_media); // Player takes its own reference
    __android_log_print(ANDROID_LOG_INFO, TAG, "Set custom media to MediaPlayer and released local ref.");

    // Set renderer
    int renderer_ret = libvlc_media_player_set_renderer(nativeMediaPlayer, nativeRendererItem, nullptr);
    if (renderer_ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "libvlc_media_player_set_renderer failed with code %d", renderer_ret);
        // Media is already set on player, player will release it.
        // Opaque data will be cleaned by close_cb eventually.
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Set renderer to MediaPlayer.");

    // Play
    int play_ret = libvlc_media_player_play(nativeMediaPlayer);
    if (play_ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "libvlc_media_player_play failed with code %d", play_ret);
        // Media and renderer are set. Player will handle cleanup.
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Called play on MediaPlayer.");

    return JNI_TRUE;
}
