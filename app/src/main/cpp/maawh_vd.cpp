// MaaWH 虚拟屏预览：GPU 零拷贝直渲（对标 MAA-Meow 的 bridge_preview.cpp）
//
// 数据流：ShellUserService 的 ImageReader 收到虚拟屏帧 → onImageAvailable 回调线程
// 把帧的 HardwareBuffer 交给 nvDrawFrame → AHardwareBuffer 转 EGLImage 挂成
// GL_TEXTURE_EXTERNAL_OES → 着色器画到 App 经 Binder 传来的预览 Surface 上。
// 全程帧数据不出 GPU 内存，无 JPEG 编解码、无跨进程像素传输、无轮询；
// 游戏出多少帧就画多少帧（帧率上限即游戏帧率）。
//
// 线程模型（刻意无渲染线程）：
//   - nvDrawFrame 只在 ImageReader 回调线程（服务端专用 HandlerThread）被调，
//     EGL 上下文始终绑定在该线程；
//   - 窗口挂载/摘除来自 Binder 线程，只改 pending 状态，由下一次 nvDrawFrame
//     开头统一 apply（换窗口的 EGLSurface 销毁顺序照抄 MAA-Meow 的教训：
//     先切到新 surface 再销毁旧的，销毁 current surface 是未定义行为）。
// 任何失败路径都返回 false，由 Java 层/App 端降级回 JPEG 轮询预览。

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
// EGLImage / HardwareBuffer 相关扩展函数需要显式开原型宏，否则只有类型没有函数声明
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <atomic>
#include <mutex>

#define LOG_TAG "maawh_vd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mtx;                    // 保护下面四个窗口状态（渲染线程读、Binder 线程写）
ANativeWindow* g_window = nullptr;   // 当前渲染窗口
ANativeWindow* g_pending = nullptr;  // 待挂载窗口
bool g_pendingDetach = false;

EGLDisplay g_dpy = EGL_NO_DISPLAY;
EGLContext g_ctx = EGL_NO_CONTEXT;
EGLConfig g_cfg = nullptr;
EGLSurface g_esurf = EGL_NO_SURFACE;
GLuint g_prog = 0;
GLuint g_tex = 0;
GLint g_uUV = -1;
int g_vpW = 0, g_vpH = 0;            // viewport 已按此窗口尺寸设置

std::atomic<long> g_drawn{0};

const char* kVertSrc =
    "attribute vec2 aPos;\n"
    "varying vec2 vUV;\n"
    "uniform vec4 uUV;\n"            // x0,y0,x1,y1：aspect-fill 时对源做居中裁剪
    "void main() {\n"
    "    gl_Position = vec4(aPos, 0.0, 1.0);\n"
    // t=0 是缓冲第一行（顶行），屏幕底部(aPos.y=-1)要采样底行 → y 翻转
    "    vUV = vec2(mix(uUV.x, uUV.z, (aPos.x + 1.0) * 0.5),\n"
    "               mix(uUV.w, uUV.y, (aPos.y + 1.0) * 0.5));\n"
    "}\n";

const char* kFragSrc =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;\n"
    "varying vec2 vUV;\n"
    "uniform samplerExternalOES uTex;\n"
    "void main() {\n"
    "    gl_FragColor = texture2D(uTex, vUV);\n"
    "}\n";

GLuint compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(sh, sizeof(log), nullptr, log);
        LOGW("shader compile failed: %s", log);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

bool ensureDisplay() {
    if (g_dpy != EGL_NO_DISPLAY) return true;
    g_dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_dpy == EGL_NO_DISPLAY) return false;
    if (!eglInitialize(g_dpy, nullptr, nullptr)) {
        g_dpy = EGL_NO_DISPLAY;
        return false;
    }
    // display 是进程级单例，eglTerminate 全程不调（MAA-Meow 踩过的坑：terminate 会把它整个作废）
    const EGLint cfgAttrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLint n = 0;
    if (!eglChooseConfig(g_dpy, cfgAttrs, &g_cfg, 1, &n) || n < 1) {
        LOGW("eglChooseConfig failed");
        return false;
    }
    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    g_ctx = eglCreateContext(g_dpy, g_cfg, EGL_NO_CONTEXT, ctxAttrs);
    if (g_ctx == EGL_NO_CONTEXT) {
        LOGW("eglCreateContext failed 0x%x", eglGetError());
        return false;
    }
    return true;
}

/** 切换当前窗口（win=null = 摘除）。成功后旧 EGLSurface 已销毁、旧窗口已 release。 */
bool setCurrent_l(ANativeWindow* win) {
    if (!ensureDisplay()) return false;
    EGLSurface newSurf = EGL_NO_SURFACE;
    if (win) {
        newSurf = eglCreateWindowSurface(g_dpy, g_cfg, (EGLNativeWindowType)win, nullptr);
        if (newSurf == EGL_NO_SURFACE) {
            LOGW("eglCreateWindowSurface failed 0x%x", eglGetError());
            return false;
        }
    }
    // 先切到新 surface 再销毁旧的
    if (!eglMakeCurrent(g_dpy,
                        newSurf != EGL_NO_SURFACE ? newSurf : EGL_NO_SURFACE,
                        newSurf != EGL_NO_SURFACE ? newSurf : EGL_NO_SURFACE,
                        win ? g_ctx : EGL_NO_CONTEXT)) {
        LOGW("eglMakeCurrent failed 0x%x", eglGetError());
        if (newSurf != EGL_NO_SURFACE) eglDestroySurface(g_dpy, newSurf);
        return false;
    }
    if (g_esurf != EGL_NO_SURFACE) eglDestroySurface(g_dpy, g_esurf);
    if (g_window) ANativeWindow_release(g_window);
    g_esurf = newSurf;
    g_window = win;
    g_vpW = g_vpH = 0;
    return true;
}

bool ensureGlObjects() {
    if (g_prog) return true;
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragSrc);
    if (!vs || !fs) return false;
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
        LOGW("program link failed: %s", log);
        glDeleteProgram(prog);
        return false;
    }
    g_prog = prog;
    g_uUV = glGetUniformLocation(g_prog, "uUV");
    glGenTextures(1, &g_tex);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_tex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUseProgram(g_prog);
    GLint uTex = glGetUniformLocation(g_prog, "uTex");
    glUniform1i(uTex, 0);
    // 全屏四边形（triangle strip），aPos 由 generic vertex attrib 0 常驻供着
    static const GLfloat quad[] = { -1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f };
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
    glEnableVertexAttribArray(0);
    glDisable(GL_BLEND);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    return true;
}

} // namespace

extern "C" {

/** 挂载预览窗口（App 进程 SurfaceView 的 Surface 经 Binder 传来）。返回 false = 拿不到窗口。 */
JNIEXPORT jboolean JNICALL
Java_com_maawh_app_ShellUserService_nvAttachSurface(JNIEnv* env, jclass, jobject jsurface) {
    if (!jsurface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, jsurface);
    if (!win) {
        LOGW("ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        if (g_pending) ANativeWindow_release(g_pending);
        g_pending = win;
        g_pendingDetach = false;
    }
    LOGI("preview surface pending attach");
    return JNI_TRUE;
}

/** 摘除预览窗口（幂等；实际销毁在下一次 nvDrawFrame 开头执行——那里才碰 EGL）。 */
JNIEXPORT void JNICALL
Java_com_maawh_app_ShellUserService_nvDetachSurface(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mtx);
    if (g_pending) {
        ANativeWindow_release(g_pending);
        g_pending = nullptr;
    }
    g_pendingDetach = true;
}

/**
 * 渲染一帧（ImageReader 回调线程同步调用）。
 * 返回 false：当前无预览窗口或渲染链路失败，调用方直接 close 帧即可。
 */
JNIEXPORT jboolean JNICALL
Java_com_maawh_app_ShellUserService_nvDrawFrame(JNIEnv* env, jclass, jobject jhb) {
    if (!jhb) return JNI_FALSE;
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, jhb);
    if (!hb) return JNI_FALSE;

    ANativeWindow* win;
    {
        std::lock_guard<std::mutex> lk(g_mtx);
        if (g_pending || g_pendingDetach) {
            ANativeWindow* want = g_pending;
            bool detach = g_pendingDetach;
            g_pending = nullptr;
            g_pendingDetach = false;
            setCurrent_l(want);   // want=null → 摘除
        }
        win = g_window;
        if (!win) return JNI_FALSE;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hb, &desc);
    const int bw = (int)desc.width, bh = (int)desc.height;
    if (bw <= 0 || bh <= 0) return JNI_FALSE;

    if (!ensureGlObjects()) return JNI_FALSE;

    const int ww = ANativeWindow_getWidth(win);
    const int wh = ANativeWindow_getHeight(win);
    if (ww <= 0 || wh <= 0) return JNI_FALSE;
    if (ww != g_vpW || wh != g_vpH) {
        glViewport(0, 0, ww, wh);
        g_vpW = ww;
        g_vpH = wh;
    }

    EGLClientBuffer cb = eglGetNativeClientBufferANDROID(hb);
    if (!cb) return JNI_FALSE;
    const EGLint imgAttrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR eimg = eglCreateImageKHR(g_dpy, EGL_NO_CONTEXT,
                                         EGL_NATIVE_BUFFER_ANDROID, cb, imgAttrs);
    if (eimg == EGL_NO_IMAGE_KHR) {
        static std::atomic<int> warnOnce{0};
        if (warnOnce.fetch_add(1) < 3) {
            LOGW("eglCreateImageKHR failed 0x%x (buf usage=0x%llx)", eglGetError(),
                 (unsigned long long)desc.usage);
        }
        return JNI_FALSE;
    }

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_tex);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)eimg);

    // aspect-fill：窗口比例和源不一致时裁掉源的多余部分（等价 Bitmap centerCrop 语义）
    const float wa = (float)ww / (float)wh;
    const float ba = (float)bw / (float)bh;
    float x0 = 0.f, y0 = 0.f, x1 = 1.f, y1 = 1.f;
    if (ba > wa) {
        const float c = wa / ba;
        x0 = (1.f - c) * 0.5f;
        x1 = 1.f - x0;
    } else if (ba < wa) {
        const float c = ba / wa;
        y0 = (1.f - c) * 0.5f;
        y1 = 1.f - y0;
    }
    glUseProgram(g_prog);
    glUniform4f(g_uUV, x0, y0, x1, y1);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    eglSwapBuffers(g_dpy, g_esurf);
    eglDestroyImageKHR(g_dpy, eimg);
    g_drawn.fetch_add(1, std::memory_order_relaxed);
    return JNI_TRUE;
}

/** 已成功渲染的帧计数（App 端确认直渲通路健康，不健康就降级回 JPEG 轮询）。 */
JNIEXPORT jlong JNICALL
Java_com_maawh_app_ShellUserService_nvFrameCount(JNIEnv*, jclass) {
    return (jlong)g_drawn.load(std::memory_order_relaxed);
}

} // extern "C"
