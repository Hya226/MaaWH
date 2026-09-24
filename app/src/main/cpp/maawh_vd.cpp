// MaaWH 虚拟屏预览：GPU 零拷贝直渲（对标 MAA-Meow 的 bridge_preview.cpp）
//
// 数据流：ShellUserService 的 ImageReader 收到虚拟屏帧 → onImageAvailable 回调把帧的
// HardwareBuffer 投递给渲染线程 → AHardwareBuffer 转 EGLImage 挂成 GL_TEXTURE_EXTERNAL_OES
// → 着色器画到 App 经 Binder 传来的预览 Surface 上。
// 全程帧数据不出 GPU 内存，无 JPEG 编解码、无跨进程像素传输、无轮询；
// 游戏出多少帧就画多少帧（帧率上限即游戏帧率）。
//
// ★ 线程模型（2026-09-24 教训，勿改回同步渲染）：
//   EGL 上下文只允许同时属于一个线程；跨线程 eglMakeCurrent 会报 EGL_BAD_ACCESS
//   （0x3002），"在 Binder 线程补画/切窗口"会静默失败并吞掉 pending 窗口切换，
//   渲染目标从此卡死在已销毁的旧窗口上（实测：进全屏后画面冻结 + 永久降级）。
//   因此所有 EGL 操作都收敛到唯一的渲染线程：帧经无锁队列投递（只保留最新一帧，
//   retained 引用防生产者复用），窗口切换/补画只是渲染线程的唤醒信号；
//   切完窗口会重画队列里留存的最后一帧——静止画面挂载预览也能立刻出图。
// 任何失败路径都返回 false，由 Java 层/App 端降级回 JPEG 轮询预览。

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <EGL/egl.h>
#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>

#define LOG_TAG "maawh_vd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_qmtx;                    // 队列/窗口状态锁（生产者与渲染线程）
std::condition_variable g_qcv;

ANativeWindow* g_pending = nullptr;   // 待挂载窗口
bool g_pendingDetach = false;
bool g_windowDirty = false;           // 窗口待应用/上次应用失败（每帧重试直到成功）
AHardwareBuffer* g_frame = nullptr;   // 最新一帧（retained；渲染线程读，生产者替换）
bool g_frameDirty = false;            // 有未渲染的新帧
bool g_exit = false;

std::atomic<long> g_drawn{0};
std::atomic<bool> g_threadStarted{false};

EGLDisplay g_dpy = EGL_NO_DISPLAY;
EGLContext g_ctx = EGL_NO_CONTEXT;
EGLConfig g_cfg = nullptr;
EGLSurface g_esurf = EGL_NO_SURFACE;
ANativeWindow* g_window = nullptr;    // 当前渲染窗口（仅渲染线程触碰）
GLuint g_prog = 0;
GLuint g_tex = 0;
GLint g_uUV = -1;
int g_vpW = 0, g_vpH = 0;

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

/** 切换当前窗口（win=null = 摘除）。仅渲染线程调用。
 *  成功后旧 EGLSurface 已销毁、旧窗口已 release；失败返回 false（旧状态保持）。 */
bool setCurrent(ANativeWindow* win) {
    if (!ensureDisplay()) return false;
    EGLSurface newSurf = EGL_NO_SURFACE;
    if (win) {
        newSurf = eglCreateWindowSurface(g_dpy, g_cfg, (EGLNativeWindowType)win, nullptr);
        if (newSurf == EGL_NO_SURFACE) {
            LOGW("eglCreateWindowSurface failed 0x%x", eglGetError());
            return false;
        }
    }
    // 先切到新 surface 再销毁旧的（销毁 current surface 是未定义行为）
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

/** 渲染一帧硬件缓冲到当前窗口（仅渲染线程调用；调用方持有 hb 引用）。 */
bool drawHb(AHardwareBuffer* hb) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hb, &desc);
    const int bw = (int)desc.width, bh = (int)desc.height;
    if (bw <= 0 || bh <= 0 || !g_window) return false;

    if (!ensureGlObjects()) return false;

    const int ww = ANativeWindow_getWidth(g_window);
    const int wh = ANativeWindow_getHeight(g_window);
    if (ww <= 0 || wh <= 0) return false;
    if (ww != g_vpW || wh != g_vpH) {
        glViewport(0, 0, ww, wh);
        g_vpW = ww;
        g_vpH = wh;
    }

    EGLClientBuffer cb = eglGetNativeClientBufferANDROID(hb);
    if (!cb) return false;
    const EGLint imgAttrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR eimg = eglCreateImageKHR(g_dpy, EGL_NO_CONTEXT,
                                         EGL_NATIVE_BUFFER_ANDROID, cb, imgAttrs);
    if (eimg == EGL_NO_IMAGE_KHR) {
        static std::atomic<int> warnOnce{0};
        if (warnOnce.fetch_add(1) < 3) {
            LOGW("eglCreateImageKHR failed 0x%x (buf usage=0x%llx)", eglGetError(),
                 (unsigned long long)desc.usage);
        }
        return false;
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
    const EGLBoolean swapped = eglSwapBuffers(g_dpy, g_esurf);
    eglDestroyImageKHR(g_dpy, eimg);
    if (swapped != EGL_TRUE) return false;
    g_drawn.fetch_add(1, std::memory_order_relaxed);
    return true;
}

/** 渲染线程主体：唯一的 EGL 使用者。事件 = 换窗口 / 新帧 / 退出。 */
void renderLoop() {
    std::unique_lock<std::mutex> lk(g_qmtx);
    for (;;) {
        g_qcv.wait(lk, [] {
            return g_exit || g_windowDirty || (g_frameDirty && g_frame);
        });
        if (g_exit) return;

        // 应用窗口切换（失败保留 dirty，下一帧到达时自动重试——App 刚启动时
        // eglCreateWindowSurface 可能瞬时 EGL_BAD_ALLOC，重试即可恢复）
        bool windowApplied = false;
        if (g_windowDirty) {
            ANativeWindow* want = g_pending;
            const bool detach = g_pendingDetach && !want;
            g_pending = nullptr;
            g_pendingDetach = false;
            if (setCurrent(detach ? nullptr : want)) {
                g_windowDirty = false;
                windowApplied = true;
            }
        }
        const bool newFrame = g_frameDirty;
        g_frameDirty = false;
        AHardwareBuffer* hb = g_frame;   // retained；渲染期间锁外使用
        lk.unlock();

        // 新帧，或窗口刚切换/恢复（重画留存的最后一帧——静止画面挂载预览也能立刻出图）
        bool drawn = false;
        if ((newFrame || windowApplied) && hb) {
            drawn = drawHb(hb);
        }

        lk.lock();
        if (!drawn && hb) g_frameDirty = true;   // 没画成（如切窗失败）保留待重试
    }
}

void ensureRenderThread() {
    bool expected = false;
    if (g_threadStarted.compare_exchange_strong(expected, true)) {
        std::thread(renderLoop).detach();
    }
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
    ensureRenderThread();
    {
        std::lock_guard<std::mutex> lk(g_qmtx);
        if (g_pending) ANativeWindow_release(g_pending);
        g_pending = win;
        g_pendingDetach = false;
        g_windowDirty = true;
    }
    g_qcv.notify_all();
    LOGI("preview surface pending attach");
    return JNI_TRUE;
}

/** 摘除预览窗口（幂等）。 */
JNIEXPORT void JNICALL
Java_com_maawh_app_ShellUserService_nvDetachSurface(JNIEnv*, jclass) {
    ensureRenderThread();
    {
        std::lock_guard<std::mutex> lk(g_qmtx);
        if (g_pending) {
            ANativeWindow_release(g_pending);
            g_pending = nullptr;
        }
        g_pendingDetach = true;
        g_windowDirty = true;
    }
    g_qcv.notify_all();
}

/**
 * 投递一帧（ImageReader 回调线程调用，异步——native 端 retain 后立刻返回，
 * 真正的渲染由专职渲染线程完成）。返回 false：渲染线程没起来（罕见）。
 */
JNIEXPORT jboolean JNICALL
Java_com_maawh_app_ShellUserService_nvDrawFrame(JNIEnv* env, jclass, jobject jhb) {
    if (!jhb) return JNI_FALSE;
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, jhb);
    if (!hb) return JNI_FALSE;
    ensureRenderThread();
    {
        std::lock_guard<std::mutex> lk(g_qmtx);
        if (g_frame) AHardwareBuffer_release(g_frame);
        AHardwareBuffer_acquire(hb);        // 渲染线程用完前 Java 侧的 close 不影响
        g_frame = hb;
        g_frameDirty = true;
    }
    g_qcv.notify_all();
    return JNI_TRUE;
}

/** 已成功渲染的帧计数（App 端确认直渲通路健康，不健康就降级回 JPEG 轮询）。 */
JNIEXPORT jlong JNICALL
Java_com_maawh_app_ShellUserService_nvFrameCount(JNIEnv*, jclass) {
    return (jlong)g_drawn.load(std::memory_order_relaxed);
}

} // extern "C"
