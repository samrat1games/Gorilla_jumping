// Тонкий мост к OpenXR. Вся игра (рендер, физика, логика) написана на Kotlin; здесь только то,
// что без C не сделать: сессия, свопчейны, позы головы и рук, цикл кадра.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#define XR_USE_TIMESPEC
#include <time.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "GorillaXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Swapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t width = 0, height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
};

struct Hand {
    XrPath path = XR_NULL_PATH;
    XrSpace space = XR_NULL_HANDLE;
};

struct State {
    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace appSpace = XR_NULL_HANDLE;
    XrSpace viewSpace = XR_NULL_HANDLE;
    XrSessionState sessionState = XR_SESSION_STATE_UNKNOWN;
    bool running = false;
    bool exitRequested = false;
    Swapchain swapchains[2];
    int64_t format = 0;
    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    XrFrameState frameState = {XR_TYPE_FRAME_STATE};
    // XR_KHR_convert_timespec_time: перевод XrTime в CLOCK_MONOTONIC, чтобы знать, когда кадр
    // реально появится на экране (для предсказания рук к моменту показа).
    PFN_xrConvertTimeToTimespecTimeKHR timeToTimespec = nullptr;

    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction poseAction = XR_NULL_HANDLE;
    XrAction squeezeAction = XR_NULL_HANDLE;
    XrAction triggerAction = XR_NULL_HANDLE;
    XrAction primaryAction = XR_NULL_HANDLE;
    XrAction secondaryAction = XR_NULL_HANDLE;
    XrAction stickAction = XR_NULL_HANDLE;
    Hand hands[2];
    std::string error;
} g;

bool check(XrResult result, const char *what) {
    if (XR_SUCCEEDED(result)) return true;
    char text[XR_MAX_RESULT_STRING_SIZE] = {};
    if (g.instance != XR_NULL_HANDLE) xrResultToString(g.instance, result, text);
    else snprintf(text, sizeof(text), "%d", result);
    g.error = std::string(what) + ": " + text;
    LOGE("%s", g.error.c_str());
    return false;
}

XrPath path(const char *text) {
    XrPath p = XR_NULL_PATH;
    xrStringToPath(g.instance, text, &p);
    return p;
}

bool createAction(XrAction *action, XrActionType type, const char *name, const char *localized) {
    XrActionCreateInfo info = {XR_TYPE_ACTION_CREATE_INFO};
    info.actionType = type;
    strncpy(info.actionName, name, sizeof(info.actionName) - 1);
    strncpy(info.localizedActionName, localized, sizeof(info.localizedActionName) - 1);
    XrPath subpaths[2] = {g.hands[0].path, g.hands[1].path};
    info.countSubactionPaths = 2;
    info.subactionPaths = subpaths;
    return check(xrCreateAction(g.actionSet, &info, action), name);
}

bool setupActions() {
    XrActionSetCreateInfo setInfo = {XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(setInfo.actionSetName, "gameplay");
    strcpy(setInfo.localizedActionSetName, "Gameplay");
    if (!check(xrCreateActionSet(g.instance, &setInfo, &g.actionSet), "xrCreateActionSet")) return false;

    g.hands[0].path = path("/user/hand/left");
    g.hands[1].path = path("/user/hand/right");

    if (!createAction(&g.poseAction, XR_ACTION_TYPE_POSE_INPUT, "hand_pose", "Hand pose")) return false;
    if (!createAction(&g.squeezeAction, XR_ACTION_TYPE_FLOAT_INPUT, "squeeze", "Squeeze")) return false;
    if (!createAction(&g.triggerAction, XR_ACTION_TYPE_FLOAT_INPUT, "trigger", "Trigger")) return false;
    if (!createAction(&g.primaryAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "primary", "Primary")) return false;
    if (!createAction(&g.secondaryAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "secondary", "Secondary")) return false;
    if (!createAction(&g.stickAction, XR_ACTION_TYPE_VECTOR2F_INPUT, "stick", "Stick")) return false;

    // PhoneXR выдаёт себя за Oculus Touch (docs/manifest.txt).
    std::vector<XrActionSuggestedBinding> b = {
        {g.poseAction, path("/user/hand/left/input/grip/pose")},
        {g.poseAction, path("/user/hand/right/input/grip/pose")},
        {g.squeezeAction, path("/user/hand/left/input/squeeze/value")},
        {g.squeezeAction, path("/user/hand/right/input/squeeze/value")},
        {g.triggerAction, path("/user/hand/left/input/trigger/value")},
        {g.triggerAction, path("/user/hand/right/input/trigger/value")},
        {g.primaryAction, path("/user/hand/left/input/x/click")},
        {g.primaryAction, path("/user/hand/right/input/a/click")},
        {g.secondaryAction, path("/user/hand/left/input/y/click")},
        {g.secondaryAction, path("/user/hand/right/input/b/click")},
        {g.stickAction, path("/user/hand/left/input/thumbstick")},
        {g.stickAction, path("/user/hand/right/input/thumbstick")},
    };
    XrInteractionProfileSuggestedBinding suggested = {XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggested.interactionProfile = path("/interaction_profiles/oculus/touch_controller");
    suggested.suggestedBindings = b.data();
    suggested.countSuggestedBindings = (uint32_t)b.size();
    if (!check(xrSuggestInteractionProfileBindings(g.instance, &suggested), "suggest touch")) return false;

    // Запасной профиль, если рантайм отдаёт только простой контроллер.
    std::vector<XrActionSuggestedBinding> simple = {
        {g.poseAction, path("/user/hand/left/input/grip/pose")},
        {g.poseAction, path("/user/hand/right/input/grip/pose")},
        {g.squeezeAction, path("/user/hand/left/input/select/click")},
        {g.squeezeAction, path("/user/hand/right/input/select/click")},
    };
    suggested.interactionProfile = path("/interaction_profiles/khr/simple_controller");
    suggested.suggestedBindings = simple.data();
    suggested.countSuggestedBindings = (uint32_t)simple.size();
    xrSuggestInteractionProfileBindings(g.instance, &suggested);

    for (auto &hand : g.hands) {
        XrActionSpaceCreateInfo spaceInfo = {XR_TYPE_ACTION_SPACE_CREATE_INFO};
        spaceInfo.action = g.poseAction;
        spaceInfo.subactionPath = hand.path;
        spaceInfo.poseInActionSpace.orientation.w = 1;
        if (!check(xrCreateActionSpace(g.session, &spaceInfo, &hand.space), "xrCreateActionSpace")) return false;
    }

    XrSessionActionSetsAttachInfo attach = {XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1;
    attach.actionSets = &g.actionSet;
    return check(xrAttachSessionActionSets(g.session, &attach), "xrAttachSessionActionSets");
}

bool setupSwapchains() {
    uint32_t viewCount = 0;
    xrEnumerateViewConfigurationViews(g.instance, g.system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &viewCount, nullptr);
    if (viewCount < 2) {
        g.error = "Runtime has no stereo view configuration";
        return false;
    }
    std::vector<XrViewConfigurationView> configViews(viewCount, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
    xrEnumerateViewConfigurationViews(g.instance, g.system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, viewCount, &viewCount, configViews.data());

    uint32_t formatCount = 0;
    xrEnumerateSwapchainFormats(g.session, 0, &formatCount, nullptr);
    std::vector<int64_t> formats(formatCount);
    xrEnumerateSwapchainFormats(g.session, formatCount, &formatCount, formats.data());
    // Предпочитаем обычный RGBA8: текстуры карты уже в sRGB, так их можно выводить как есть.
    g.format = formats.empty() ? GL_RGBA8 : formats[0];
    for (int64_t f : formats) if (f == GL_RGBA8) { g.format = f; break; }
    if (g.format != GL_RGBA8) for (int64_t f : formats) if (f == GL_SRGB8_ALPHA8) { g.format = f; break; }

    for (int eye = 0; eye < 2; eye++) {
        Swapchain &sc = g.swapchains[eye];
        // Рендер в 60% рекомендуемого разрешения: в 2.8 раза меньше пикселей, видеочип остаётся трекингу рук.
        const float kRenderScale = 0.6f;
        sc.width = (int32_t)(configViews[eye].recommendedImageRectWidth * kRenderScale);
        sc.height = (int32_t)(configViews[eye].recommendedImageRectHeight * kRenderScale);
        XrSwapchainCreateInfo info = {XR_TYPE_SWAPCHAIN_CREATE_INFO};
        info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        info.format = g.format;
        info.sampleCount = 1;
        info.width = (uint32_t)sc.width;
        info.height = (uint32_t)sc.height;
        info.faceCount = 1;
        info.arraySize = 1;
        info.mipCount = 1;
        if (!check(xrCreateSwapchain(g.session, &info, &sc.handle), "xrCreateSwapchain")) return false;
        uint32_t imageCount = 0;
        xrEnumerateSwapchainImages(sc.handle, 0, &imageCount, nullptr);
        sc.images.assign(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(sc.handle, imageCount, &imageCount, (XrSwapchainImageBaseHeader *)sc.images.data());
        LOGI("eye %d swapchain %dx%d, %u images, format 0x%llx", eye, sc.width, sc.height, imageCount, (long long)g.format);
    }
    return true;
}

void writePose(float *out, const XrPosef &pose) {
    out[0] = pose.position.x;
    out[1] = pose.position.y;
    out[2] = pose.position.z;
    out[3] = pose.orientation.x;
    out[4] = pose.orientation.y;
    out[5] = pose.orientation.z;
    out[6] = pose.orientation.w;
}

float floatAction(XrAction action, XrPath sub) {
    XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = action;
    info.subactionPath = sub;
    XrActionStateFloat state = {XR_TYPE_ACTION_STATE_FLOAT};
    if (XR_FAILED(xrGetActionStateFloat(g.session, &info, &state)) || !state.isActive) return 0;
    return state.currentState;
}

float boolAction(XrAction action, XrPath sub) {
    XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = action;
    info.subactionPath = sub;
    XrActionStateBoolean state = {XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_FAILED(xrGetActionStateBoolean(g.session, &info, &state)) || !state.isActive) return 0;
    return state.currentState ? 1.f : 0.f;
}

void stickAction(XrPath sub, float *out) {
    XrActionStateGetInfo info = {XR_TYPE_ACTION_STATE_GET_INFO};
    info.action = g.stickAction;
    info.subactionPath = sub;
    XrActionStateVector2f state = {XR_TYPE_ACTION_STATE_VECTOR2F};
    out[0] = out[1] = 0;
    if (XR_FAILED(xrGetActionStateVector2f(g.session, &info, &state)) || !state.isActive) return;
    out[0] = state.currentState.x;
    out[1] = state.currentState.y;
}

void destroyAll() {
    for (auto &sc : g.swapchains) {
        if (sc.handle) xrDestroySwapchain(sc.handle);
        sc = Swapchain();
    }
    for (auto &hand : g.hands) {
        if (hand.space) xrDestroySpace(hand.space);
        hand = Hand();
    }
    if (g.appSpace) xrDestroySpace(g.appSpace);
    if (g.viewSpace) xrDestroySpace(g.viewSpace);
    if (g.actionSet) xrDestroyActionSet(g.actionSet);
    if (g.session) xrDestroySession(g.session);
    if (g.instance) xrDestroyInstance(g.instance);
    g = State();
}

} // namespace

// Раскладка массива кадра (FrameLayout.kt повторяет её на стороне Kotlin):
//   0      shouldRender
//   1..7   голова: позиция xyz, кватернион xyzw
//   8..18  левый глаз: позиция, кватернион, fov (left right up down)
//   19..29 правый глаз
//   30..45 левая рука:  valid, позиция, кватернион, squeeze, trigger, primary, secondary, stickX, stickY, pad
//   46..61 правая рука
//   62     через сколько секунд кадр появится на экране (predictedDisplayTime − сейчас)
//   63     период кадров дисплея, с
extern "C" {

JNIEXPORT jstring JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeInit(JNIEnv *env, jobject, jobject activity, jlong display, jlong config, jlong context) {
    destroyAll();
    JavaVM *vm = nullptr;
    env->GetJavaVM(&vm);
    jobject activityRef = env->NewGlobalRef(activity);

    PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
    xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction *)&initializeLoader);
    if (initializeLoader) {
        XrLoaderInitInfoAndroidKHR loaderInfo = {XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        loaderInfo.applicationVM = vm;
        loaderInfo.applicationContext = activityRef;
        initializeLoader((XrLoaderInitInfoBaseHeaderKHR *)&loaderInfo);
    }

    const char *extensions[3] = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME, nullptr};
    uint32_t extensionCount = 2;
    {
        uint32_t n = 0;
        xrEnumerateInstanceExtensionProperties(nullptr, 0, &n, nullptr);
        std::vector<XrExtensionProperties> props(n, {XR_TYPE_EXTENSION_PROPERTIES});
        if (n > 0 && XR_SUCCEEDED(xrEnumerateInstanceExtensionProperties(nullptr, n, &n, props.data()))) {
            for (auto &p : props) {
                if (strcmp(p.extensionName, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0) {
                    extensions[extensionCount++] = XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME;
                }
            }
        }
    }
    XrInstanceCreateInfoAndroidKHR androidInfo = {XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = activityRef;
    XrInstanceCreateInfo info = {XR_TYPE_INSTANCE_CREATE_INFO};
    info.next = &androidInfo;
    strcpy(info.applicationInfo.applicationName, "Gorilla Jumping");
    info.applicationInfo.applicationVersion = 1;
    strcpy(info.applicationInfo.engineName, "GorillaKt");
    info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 34);
    info.enabledExtensionCount = extensionCount;
    info.enabledExtensionNames = extensions;

    bool ok = check(xrCreateInstance(&info, &g.instance), "xrCreateInstance");
    if (ok && extensionCount == 3) {
        xrGetInstanceProcAddr(g.instance, "xrConvertTimeToTimespecTimeKHR", (PFN_xrVoidFunction *)&g.timeToTimespec);
        LOGI("XR_KHR_convert_timespec_time: %s", g.timeToTimespec ? "yes" : "no");
    }
    if (ok) {
        XrSystemGetInfo systemInfo = {XR_TYPE_SYSTEM_GET_INFO};
        systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        ok = check(xrGetSystem(g.instance, &systemInfo, &g.system), "xrGetSystem");
    }
    if (ok) {
        PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
        xrGetInstanceProcAddr(g.instance, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction *)&getRequirements);
        XrGraphicsRequirementsOpenGLESKHR requirements = {XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        ok = getRequirements && check(getRequirements(g.instance, g.system, &requirements), "graphics requirements");
    }
    if (ok) {
        XrGraphicsBindingOpenGLESAndroidKHR binding = {XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = (EGLDisplay)display;
        binding.config = (EGLConfig)config;
        binding.context = (EGLContext)context;
        XrSessionCreateInfo sessionInfo = {XR_TYPE_SESSION_CREATE_INFO};
        sessionInfo.next = &binding;
        sessionInfo.systemId = g.system;
        ok = check(xrCreateSession(g.instance, &sessionInfo, &g.session), "xrCreateSession");
    }
    if (ok) {
        // LOCAL: у телефона нет пола, поэтому высоту роста игра добавляет сама.
        XrReferenceSpaceCreateInfo spaceInfo = {XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        spaceInfo.poseInReferenceSpace.orientation.w = 1;
        spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        ok = check(xrCreateReferenceSpace(g.session, &spaceInfo, &g.appSpace), "local space");
        spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
        ok = ok && check(xrCreateReferenceSpace(g.session, &spaceInfo, &g.viewSpace), "view space");
    }
    ok = ok && setupActions() && setupSwapchains();
    if (!ok) {
        std::string error = g.error.empty() ? "OpenXR init failed" : g.error;
        destroyAll();
        return env->NewStringUTF(error.c_str());
    }
    return nullptr;
}

JNIEXPORT jintArray JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeSwapchainInfo(JNIEnv *env, jobject) {
    jint values[4] = {g.swapchains[0].width, g.swapchains[0].height, (jint)g.format,
                      (jint)g.swapchains[0].images.size()};
    jintArray result = env->NewIntArray(4);
    env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

// Возвращает биты: 1 — сессия идёт (можно рисовать), 2 — нужно выйти.
JNIEXPORT jint JNICALL
Java_com_gorillajumping_xr_XrBridge_nativePollEvents(JNIEnv *, jobject) {
    XrEventDataBuffer event = {XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(g.instance, &event) == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto *changed = (XrEventDataSessionStateChanged *)&event;
            g.sessionState = changed->state;
            LOGI("session state %d", g.sessionState);
            if (g.sessionState == XR_SESSION_STATE_READY) {
                XrSessionBeginInfo begin = {XR_TYPE_SESSION_BEGIN_INFO};
                begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                g.running = check(xrBeginSession(g.session, &begin), "xrBeginSession");
            } else if (g.sessionState == XR_SESSION_STATE_STOPPING) {
                xrEndSession(g.session);
                g.running = false;
            } else if (g.sessionState == XR_SESSION_STATE_EXITING || g.sessionState == XR_SESSION_STATE_LOSS_PENDING) {
                g.exitRequested = true;
            }
        } else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
            g.exitRequested = true;
        }
        event = {XR_TYPE_EVENT_DATA_BUFFER};
    }
    return (g.running ? 1 : 0) | (g.exitRequested ? 2 : 0);
}

JNIEXPORT void JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeRequestExit(JNIEnv *, jobject) {
    if (g.session) xrRequestExitSession(g.session);
}

JNIEXPORT jboolean JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeBeginFrame(JNIEnv *env, jobject, jfloatArray outArray) {
    float out[64] = {};
    g.frameState = {XR_TYPE_FRAME_STATE};
    if (!check(xrWaitFrame(g.session, nullptr, &g.frameState), "xrWaitFrame")) return JNI_FALSE;
    if (!check(xrBeginFrame(g.session, nullptr), "xrBeginFrame")) return JNI_FALSE;
    XrTime time = g.frameState.predictedDisplayTime;

    XrActiveActionSet active = {g.actionSet, XR_NULL_PATH};
    XrActionsSyncInfo sync = {XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    xrSyncActions(g.session, &sync);

    XrSpaceLocation head = {XR_TYPE_SPACE_LOCATION};
    xrLocateSpace(g.viewSpace, g.appSpace, time, &head);
    writePose(out + 1, head.pose);

    XrViewLocateInfo locate = {XR_TYPE_VIEW_LOCATE_INFO};
    locate.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locate.displayTime = time;
    locate.space = g.appSpace;
    XrViewState viewState = {XR_TYPE_VIEW_STATE};
    uint32_t count = 0;
    g.views[0] = {XR_TYPE_VIEW};
    g.views[1] = {XR_TYPE_VIEW};
    xrLocateViews(g.session, &locate, &viewState, 2, &count, g.views);
    for (int eye = 0; eye < 2; eye++) {
        float *e = out + 8 + eye * 11;
        writePose(e, g.views[eye].pose);
        e[7] = g.views[eye].fov.angleLeft;
        e[8] = g.views[eye].fov.angleRight;
        e[9] = g.views[eye].fov.angleUp;
        e[10] = g.views[eye].fov.angleDown;
    }

    for (int i = 0; i < 2; i++) {
        float *h = out + 30 + i * 16;
        XrSpaceLocation loc = {XR_TYPE_SPACE_LOCATION};
        xrLocateSpace(g.hands[i].space, g.appSpace, time, &loc);
        bool valid = (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;
        h[0] = valid ? 1.f : 0.f;
        writePose(h + 1, loc.pose);
        h[8] = floatAction(g.squeezeAction, g.hands[i].path);
        h[9] = floatAction(g.triggerAction, g.hands[i].path);
        h[10] = boolAction(g.primaryAction, g.hands[i].path);
        h[11] = boolAction(g.secondaryAction, g.hands[i].path);
        stickAction(g.hands[i].path, h + 12);
    }

    out[0] = g.frameState.shouldRender ? 1.f : 0.f;
    float period = (float)g.frameState.predictedDisplayPeriod * 1e-9f;
    float ahead = period * 1.5f; // без расширения: типичный горизонт рантайма
    if (g.timeToTimespec) {
        timespec display = {}, now = {};
        if (XR_SUCCEEDED(g.timeToTimespec(g.instance, time, &display)) && clock_gettime(CLOCK_MONOTONIC, &now) == 0) {
            int64_t d = (int64_t)display.tv_sec * 1000000000LL + display.tv_nsec - ((int64_t)now.tv_sec * 1000000000LL + now.tv_nsec);
            ahead = (float)d * 1e-9f;
        }
    }
    out[62] = ahead < 0.f ? 0.f : (ahead > 0.1f ? 0.1f : ahead);
    out[63] = period;
    env->SetFloatArrayRegion(outArray, 0, 64, out);
    return g.frameState.shouldRender ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeAcquire(JNIEnv *, jobject, jint eye) {
    Swapchain &sc = g.swapchains[eye];
    uint32_t index = 0;
    XrSwapchainImageAcquireInfo acquire = {XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    if (!check(xrAcquireSwapchainImage(sc.handle, &acquire, &index), "acquire")) return 0;
    XrSwapchainImageWaitInfo wait = {XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wait.timeout = XR_INFINITE_DURATION;
    if (!check(xrWaitSwapchainImage(sc.handle, &wait), "wait image")) return 0;
    return (jint)sc.images[index].image;
}

JNIEXPORT void JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeRelease(JNIEnv *, jobject, jint eye) {
    XrSwapchainImageReleaseInfo release = {XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    xrReleaseSwapchainImage(g.swapchains[eye].handle, &release);
}

JNIEXPORT void JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeEndFrame(JNIEnv *, jobject, jboolean rendered) {
    XrCompositionLayerProjectionView projViews[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW},
                                                     {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    XrCompositionLayerProjection layer = {XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    const XrCompositionLayerBaseHeader *layers[1] = {(XrCompositionLayerBaseHeader *)&layer};
    XrFrameEndInfo end = {XR_TYPE_FRAME_END_INFO};
    end.displayTime = g.frameState.predictedDisplayTime;
    end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    if (rendered) {
        for (int eye = 0; eye < 2; eye++) {
            projViews[eye].pose = g.views[eye].pose;
            projViews[eye].fov = g.views[eye].fov;
            projViews[eye].subImage.swapchain = g.swapchains[eye].handle;
            projViews[eye].subImage.imageRect.extent = {g.swapchains[eye].width, g.swapchains[eye].height};
        }
        layer.space = g.appSpace;
        layer.viewCount = 2;
        layer.views = projViews;
        end.layerCount = 1;
        end.layers = layers;
    }
    check(xrEndFrame(g.session, &end), "xrEndFrame");
}

JNIEXPORT void JNICALL
Java_com_gorillajumping_xr_XrBridge_nativeDestroy(JNIEnv *, jobject) {
    destroyAll();
}

} // extern "C"
