package com.maawh.app;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 运行在 Shizuku（shell/root UID）进程中的服务，由 Shizuku 服务器反射实例化。
 *
 * 注意：必须使用纯 Java 实现——Shizuku 用自己的类加载器加载本类，
 * 不保证能解析 Kotlin 运行时库。此进程不是正常 App 进程，勿依赖 Android 框架能力。
 *
 * 保留类名（混淆规则 app/proguard-rules.pro 中已 keep 本类）。
 */
public class ShellUserService extends IUserService.Stub {

    private Context mContext;

    static {
        // 允许在 Shizuku(shell) 进程内反射调用隐藏 API
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
        } catch (Throwable ignored) {
        }
    }

    /** 反射必需：无参构造（旧版 Shizuku 使用） */
    public ShellUserService() {
    }

    /** v13+ Shizuku 优先使用带 Context 的构造 */
    public ShellUserService(Context context) {
        mContext = context;
    }

    @Override
    public String virtualProbe() {
        StringBuilder sb = new StringBuilder();
        sb.append("api=").append(android.os.Build.VERSION.SDK_INT).append('\n');

        // 1) 当前系统已有哪些显示
        try {
            if (mContext != null) {
                android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
                android.view.Display[] ds = dm.getDisplays();
                sb.append("displays=").append(ds.length).append('\n');
                for (android.view.Display d : ds) {
                    sb.append("  id=").append(d.getDisplayId())
                      .append(" name=").append(d.getName()).append('\n');
                }
            } else {
                sb.append("ctx=null\n");
            }
        } catch (Throwable t) {
            sb.append("display_err=").append(t).append('\n');
        }

        // 2) 尝试虚拟设备/虚拟显示器创建入口（纯反射，多个类名/服务名都试）
        try {
            if (mContext != null) {
                String[] svcNames = { "virtualdevice", "display", "VirtualDeviceManager" };
                for (String svcName : svcNames) {
                    Object svc = null;
                    try {
                        svc = mContext.getSystemService(svcName);
                    } catch (Throwable ignored) {
                    }
                    if (svc == null) continue;
                    sb.append("svc=").append(svcName).append(" present\n");
                    // 方法清单（含 virtual 关键字）
                    for (java.lang.reflect.Method m : svc.getClass().getMethods()) {
                        if (!m.getName().toLowerCase().contains("virtual")) continue;
                        StringBuilder sig = new StringBuilder(m.getName()).append('(');
                        Class<?>[] pt = m.getParameterTypes();
                        for (int k = 0; k < pt.length; k++) {
                            if (k > 0) sig.append(',');
                            sig.append(pt[k].getSimpleName());
                        }
                        sig.append(')');
                        sb.append("method ").append(sig).append('\n');
                    }
                    boolean tried = false;
                    for (java.lang.reflect.Method m : svc.getClass().getMethods()) {
                        String n = m.getName();
                        if (!n.toLowerCase().contains("virtual") && !n.toLowerCase().contains("createvirtual")) continue;
                        if (!n.startsWith("create")) continue;
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt.length < 1) continue;
                        tried = true;
                        Object[] args = buildArgs(pt);
                        if (args == null) continue;
                        try {
                            Object r = m.invoke(svc, args);
                            sb.append("create_try=").append(n).append(" OK ").append(r).append('\n');
                        } catch (Throwable t) {
                            sb.append("create_try=").append(n)
                              .append(" err=").append(t.getCause() != null ? t.getCause().toString() : t.toString())
                              .append('\n');
                        }
                    }
                    if (!tried) {
                        // 尝试 getSystemService 后按常见签名反射 createVirtualDisplay
                        try {
                            java.lang.reflect.Method m = svc.getClass().getMethod("createVirtualDisplay",
                                String.class, int.class, int.class, int.class);
                            Object r = m.invoke(svc, "MaaWH-vd", 1080, 480, 160);
                            sb.append("createVirtualDisplay OK ").append(r).append('\n');
                        } catch (Throwable t) {
                            sb.append("createVirtualDisplay err=")
                              .append(t.getCause() != null ? t.getCause().toString() : t.toString()).append('\n');
                        }
                    }
                    // 从 createVirtualDevice 参数类型推导 Params 与其 Builder 方法
                    try {
                        for (java.lang.reflect.Method mm : svc.getClass().getMethods()) {
                            if (!mm.getName().equals("createVirtualDevice")) continue;
                            Class<?> pCls = mm.getParameterTypes()[1];
                            sb.append("paramsClass=").append(pCls.getName()).append('\n');
                            Class<?> bCls = Class.forName(pCls.getName() + "$Builder");
                            for (java.lang.reflect.Method m : bCls.getMethods()) {
                                if (m.getDeclaringClass() == Object.class) continue;
                                StringBuilder s = new StringBuilder("  b.").append(m.getName()).append('(');
                                Class<?>[] pt = m.getParameterTypes();
                                for (int k = 0; k < pt.length; k++) {
                                    if (k > 0) s.append(',');
                                    s.append(pt[k].getSimpleName());
                                }
                                s.append(')');
                                if (m.getReturnType() != Void.TYPE) s.append(" -> ").append(m.getReturnType().getSimpleName());
                                sb.append(s).append('\n');
                            }
                        }
                    } catch (Throwable t) {
                        sb.append("params_err=").append(t).append('\n');
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            sb.append("vdm_err=").append(t).append('\n');
        }

        // 3) 反射 DisplayManagerGlobal.getInstance() 探测隐藏入口
        try {
            Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
            java.lang.reflect.Method get = g.getMethod("getInstance");
            Object inst = get.invoke(null);
            sb.append("dmg=").append(inst != null).append('\n');
            try {
                java.lang.reflect.Method ids = g.getMethod("getDisplayIds", boolean.class);
                Object arr = ids.invoke(inst, true);
                if (arr instanceof int[]) {
                    sb.append("dmg_ids=").append(((int[]) arr).length).append('\n');
                }
            } catch (Throwable t) {
                sb.append("dmg_ids_err=").append(t.getClass().getSimpleName()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("dmg_err=").append(t).append('\n');
        }

        // 4) 仿 MAA-Meow：真正尝试创建一块虚拟屏（ImageReader 承接画面）
        try {
            android.hardware.display.VirtualDisplay vd = createVdProbe(sb);
            if (vd != null) {
                sb.append("VD_OK displayId=").append(vd.getDisplay().getDisplayId())
                  .append(" ").append(vd.getDisplay().getWidth()).append('x')
                  .append(vd.getDisplay().getHeight()).append('\n');
                try {
                    vd.release();
                    sb.append("VD released\n");
                } catch (Throwable t) {
                    sb.append("VD release err=").append(t).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("VD_ERR=").append(t).append('\n');
        }

        return sb.toString();
    }

    private static final int VD_FLAG_PUBLIC = 1;
    private static final int VD_FLAG_OWN_CONTENT_ONLY = 1 << 2;
    private static final int VD_FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int VD_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VD_FLAG_TRUSTED = 1 << 10;
    private static final int VD_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VD_FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int VD_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int VD_FLAG_OWN_FOCUS = 1 << 14;
    private static final int VD_FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;
    private static final int VD_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 << 16;

    /** 建虚拟屏的 flag 名单（反射取真实常量，避免手写位数出错；本 ROM 没有的常量自动跳过）。
     *  ★ TRUSTED 要签名权限 ADD_TRUSTED_DISPLAY：Android 12/12L 的 com.android.shell 没这个
     *  权限，带上它会被 DisplayManagerService 直接拒（`SecurityException: Requires
     *  ADD_TRUSTED_DISPLAY permission to create a trusted virtual display.`）→ 整块虚拟屏建不出来，
     *  用户侧表现是预览全黑 + 引擎退回截物理屏（识别全废、点击打在手机屏幕上）。Android 13
     *  起 shell 才被授予该权限（scrcpy 也是据此把 TRUSTED 门控在 13+），所以按版本决定传不传。 */
    private static final String[] VD_FLAG_NAMES = {
        "VIRTUAL_DISPLAY_FLAG_PUBLIC",
        "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
        "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH",
        "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
        "VIRTUAL_DISPLAY_FLAG_TRUSTED",
        "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
        "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED",
        "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED",
        "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS",
        "VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP",
        "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED"
    };

    /** 建屏 flag 的降级档位。★ 为什么做阶梯而不是一次判定：Android 12 上同一个签名权限
     *  ADD_TRUSTED_DISPLAY 卡住**两个** flag——TRUSTED 报 "trusted virtual display"，
     *  OWN_DISPLAY_GROUP 报 "which is not in the default DisplayGroup"；而每个 ROM 究竟卡哪几个
     *  flag 并不一致，这类机器又常常不在手边（一轮排查要等用户回传日志），所以从起始档位起
     *  被拒就自动下一档，最终档位写进返回串（`vd_flags=full|no-trusted|no-priv|minimal`）。
     *  0 full=能反射到的全给（13+ 的历史行为）/ 1 去掉 TRUSTED / 2 再去掉 OWN_DISPLAY_GROUP
     *  / 3 minimal=只留建屏与投屏必需（PUBLIC + OWN_CONTENT_ONLY + SUPPORTS_TOUCH）。 */
    private static final String[] VD_TIER_NAMES = { "full", "no-trusted", "no-priv", "minimal" };

    /** 起始档位：Android 13(T) 起 com.android.shell 才有 ADD_TRUSTED_DISPLAY，12/12L 直接跳过有权限要求的那两档 */
    private static int vdStartTier() {
        return android.os.Build.VERSION.SDK_INT >= 33 ? 0 : 2;
    }

    private static boolean vdFlagRequired(String n) {
        return n.equals("VIRTUAL_DISPLAY_FLAG_PUBLIC")
            || n.equals("VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY")
            || n.equals("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH");
    }

    private static int vdFlags(int tier) {
        int flags = 0;
        for (String n : VD_FLAG_NAMES) {
            if (tier >= 1 && n.contains("TRUSTED")) continue;
            if (tier >= 2 && n.contains("OWN_DISPLAY_GROUP")) continue;
            if (tier >= 3 && !vdFlagRequired(n)) continue;
            try {
                flags |= android.hardware.display.DisplayManager.class.getField(n).getInt(null);
            } catch (Throwable ignored) {
            }
        }
        return flags;
    }

    private android.hardware.display.VirtualDisplay createVdProbe(StringBuilder sb) {
        try {
            // 与生产路径同口径取 flag（探针要能反映真实建屏条件，别再单独攒一份）
            int flags = vdFlags(vdStartTier());
            sb.append("vd_flags=0x").append(Integer.toHexString(flags)).append('\n');

            int w = 1080, h = 1920, dpi = 320;
            android.view.Surface surf = null;
            try {
                android.media.ImageReader ir = android.media.ImageReader.newInstance(w, h,
                        android.graphics.PixelFormat.RGBA_8888, 2);
                surf = ir.getSurface();
            } catch (Throwable t) {
                sb.append("imgreader_err=").append(t).append('\n');
                return null;
            }

            java.lang.reflect.Constructor<android.hardware.display.DisplayManager> ctor =
                android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            android.hardware.display.DisplayManager dm = ctor.newInstance(new ShellCtx(mContext));
            return dm.createVirtualDisplay("MaaWH-vd-probe", w, h, dpi, surf, flags);
        } catch (Throwable t) {
            sb.append("createVdStep_err=").append(t).append('\n');
            return null;
        }
    }

    /** 伪装为 com.android.shell（SHELL_UID），与 Shizuku shell 进程调用方一致 */
    private static final class ShellCtx extends android.content.ContextWrapper {
        ShellCtx(Context base) { super(base); }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public android.content.AttributionSource getAttributionSource() {
            return new android.content.AttributionSource.Builder(android.os.Process.SHELL_UID)
                .setPackageName("com.android.shell").build();
        }
    }


    @Override
    public int exec(String[] cmd, ParcelFileDescriptor stdout) {
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            Process proc = new ProcessBuilder(resolve(cmd)).start();
            final OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(stdout);

            // stdout -> 管道（App 端并发读，避免管道写满死锁）。注意：此处不关闭 out，
            // 需等进程结束后统一关闭，App 端才能读到 EOF（见下）。
            Thread copyThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = proc.getInputStream().read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } catch (IOException ignored) {
                }
            });

            // stderr -> 内存缓冲；失败时回传 App 便于诊断
            Thread errThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = proc.getErrorStream().read(buf)) > 0) {
                        errBuf.write(buf, 0, n);
                    }
                } catch (IOException ignored) {
                }
            });

            copyThread.start();
            errThread.start();
            int code = proc.waitFor();
            copyThread.join(10_000);
            errThread.join(10_000);

            if (code != 0) {
                try {
                    out.write(errBuf.toByteArray());
                } catch (IOException ignored) {
                }
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
            return code;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    /** 只给第一个参数（可执行文件）补 /system/bin 前缀，其余参数原样保留。 */
    private volatile android.hardware.display.VirtualDisplay mVd;
    private volatile android.media.ImageReader mReader;
    private volatile int mVdId = -1;

    // ===== 虚拟屏预览 GPU 零拷贝直渲（libmaawh_vd.so，见 src/main/cpp/maawh_vd.cpp） =====
    // 预览 Surface 由 App 进程经 AIDL 传进来（Surface 可 Parcelable 跨进程），服务端在
    // ImageReader 的 onImageAvailable 回调线程里把每帧的 HardwareBuffer 用 EGL 直绘上去：
    // 帧数据不出 GPU，无 JPEG、无跨进程像素传输，帧率上限只受游戏渲染速度限制（≈60fps）。
    // 任何一环失败（lib 加载失败/旧系统拿不到 HardwareBuffer/EGL 初始化失败）都走
    // mPreviewOn=false 快路径，App 端凭 previewFrameCount 不增长自动降级回 JPEG 轮询。

    private static volatile boolean sNativeOk = false;

    /** 懒加载 native 库：优先按 App 的 nativeLibraryDir 绝对路径（本进程是 Shizuku 拉起的
     *  app_process，System.loadLibrary 的搜索路径未必包含 APK 的 lib 目录），loadLibrary 兜底。 */
    private synchronized boolean ensureNative() {
        if (sNativeOk) return true;
        try {
            if (mContext != null) {
                String dir = mContext.getApplicationInfo().nativeLibraryDir;
                if (dir != null) {
                    System.load(dir + "/libmaawh_vd.so");
                    sNativeOk = true;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            System.loadLibrary("maawh_vd");
            sNativeOk = true;
        } catch (Throwable ignored) {
        }
        return sNativeOk;
    }

    private static native boolean nvAttachSurface(android.view.Surface surface);
    private static native void nvDetachSurface();
    private static native boolean nvDrawFrame(android.hardware.HardwareBuffer hb);
    private static native long nvFrameCount();

    private volatile boolean mPreviewOn = false;
    private android.os.HandlerThread mPreviewThread;
    private android.os.Handler mPreviewHandler;

    // ===== 引擎帧缓存（对标 MAA-Meow 的 bridge_frame_buffer） =====
    // 回调线程是帧池的唯一消费者：每帧把像素搬进 mLastFrame，引擎截图（grabVirtualFrame）
    // 永远从缓存取、绝不碰 acquireLatestImage。★ 为什么必须这样：预览以 30fps 持续消费
    // 每一帧之后，画面一旦静止（公告页、挂机主页）帧池就是空的，引擎再 acquire 永远拿
    // 到 null → 截图失败 → 识别全瘫（2026-09-24 首装实测：收口关不掉公告弹窗就是它）。
    // 有缓存后，静止画面也能秒出最后一帧。3.7MB 拷贝在回调线程做，约 0.3ms。
    private final Object mFrameLock = new Object();
    private byte[] mLastFrame;          // 连续 RGBA（w*h*4），最新帧
    private int mFrameW, mFrameH;
    private boolean mFrameHas;

    /** 已从帧池消费的帧计数（看门狗用：consumed 涨而 drawn 停 = 渲染器真挂；
     *  两者都停 = 游戏画面静止，属正常不该降级） */
    private volatile long mConsumedCount = 0;

    private void updateFrameCache(android.media.Image img) {
        android.media.Image.Plane p = img.getPlanes()[0];
        java.nio.ByteBuffer buf = p.getBuffer();
        int w = img.getWidth();
        int h = img.getHeight();
        int ps = p.getPixelStride();
        int rs = p.getRowStride();
        synchronized (mFrameLock) {
            if (mLastFrame == null || mFrameW != w || mFrameH != h) {
                mLastFrame = new byte[w * h * 4];
                mFrameW = w;
                mFrameH = h;
                mFrameHas = false;
            }
            if (ps == 4 && rs == w * 4) {
                // 常规：无行填充，整块搬运
                buf.position(0);
                buf.get(mLastFrame, 0, w * h * 4);
            } else {
                // 有行填充：逐行搬到正确偏移
                for (int y = 0; y < h; y++) {
                    buf.position(y * rs);
                    buf.get(mLastFrame, y * w * 4, w * 4);
                }
            }
            mFrameHas = true;
        }
    }

    /** 每来一帧：先更新引擎帧缓存，再 GPU 直绘预览（本回调线程即渲染线程，刻意不再
     *  单开渲染线程：帧的生命周期止于本方法，没有跨线程缓冲所有权问题；
     *  acquireLatestImage 天然「只处理最新帧」，渲染慢于出帧时中间帧自动丢弃）。 */
    private final android.media.ImageReader.OnImageAvailableListener mOnFrame =
        new android.media.ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(android.media.ImageReader reader) {
                android.media.Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img == null) return;
                    mConsumedCount++;
                    updateFrameCache(img);   // 引擎截图的数据源，无论预览开没开都要更新
                    if (mPreviewOn && android.os.Build.VERSION.SDK_INT >= 26) {
                        android.hardware.HardwareBuffer hb = img.getHardwareBuffer();
                        if (hb != null) {
                            try {
                                nvDrawFrame(hb);
                            } finally {
                                hb.close();  // native 端同步画完才返回，引用已不需要
                            }
                        }
                    }
                    img.close();
                    img = null;
                } catch (Throwable t) {
                    android.util.Log.w("MaaWH", "preview frame err", t);
                } finally {
                    if (img != null) {
                        try { img.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        };

    // 常量统一收口在 MaaConst（同 APK 同 classloader，Java 可直接引用其静态字段），
    // 别在这里再写一份数值——虚拟屏尺寸改了模板基准就废，游戏包名两份不一致会查半天
    private static final int VD_W = MaaConst.VD_W;
    private static final int VD_H = MaaConst.VD_H;
    private static final int VD_DPI = MaaConst.VD_DPI;

    private static final String GAME_PKG = MaaConst.GAME_PKG;

    @Override
    public synchronized String startVirtualGame() {
        StringBuilder sb = new StringBuilder();
        try {
            if (mVd == null) {
                int w = VD_W, h = VD_H, dpi = VD_DPI; // 1280x720：与 whmx 基准帧同尺寸
                // API 29+：给 ImageReader 显式加 GPU_SAMPLED_IMAGE usage——同一块帧缓冲既可
                // CPU 读（grabVirtualFrame 引擎截图）也可作 GPU 纹理（预览零拷贝直绘）；
                // 缓冲池 3→5，预览常驻消费后引擎按需拉帧仍有余量
                android.media.ImageReader ir;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    long usage = android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN
                        | android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
                    ir = android.media.ImageReader.newInstance(w, h,
                            android.graphics.PixelFormat.RGBA_8888, 5, usage);
                } else {
                    ir = android.media.ImageReader.newInstance(w, h,
                            android.graphics.PixelFormat.RGBA_8888, 3);
                }
                // 预览回调线程：一帧到达即 GPU 直绘到挂载的预览 Surface（无预览时空转快路径）
                if (mPreviewThread == null) {
                    mPreviewThread = new android.os.HandlerThread("maawh-vd-preview");
                    mPreviewThread.start();
                    mPreviewHandler = new android.os.Handler(mPreviewThread.getLooper());
                }
                ir.setOnImageAvailableListener(mOnFrame, mPreviewHandler);
                java.lang.reflect.Constructor<android.hardware.display.DisplayManager> ctor =
                    android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
                ctor.setAccessible(true);
                android.hardware.display.DisplayManager dm = ctor.newInstance(new ShellCtx(mContext));
                // 建屏 flag 逐级降级：13+ 从 full 起（与历史行为一致），被 DMS 拒就下一档，
                // 档次见 VD_TIER_NAMES。少掉的那几个只是"待遇"（系统装饰/免触摸输入/独立
                // display group），建屏、投应用、注入触摸都不依赖它们。
                int tier = vdStartTier();
                while (true) {
                    try {
                        mVd = dm.createVirtualDisplay("MaaWH-VD", w, h, dpi, ir.getSurface(), vdFlags(tier));
                        break;
                    } catch (SecurityException | IllegalArgumentException e) {
                        if (tier >= VD_TIER_NAMES.length - 1) throw e;
                        sb.append("vd_flags_denied(").append(e.getMessage()).append(") → 降到 ")
                          .append(VD_TIER_NAMES[tier + 1]).append('\n');
                        tier++;
                    }
                }
                sb.append("vd_flags=").append(VD_TIER_NAMES[tier]).append('\n');
                mReader = ir;
                mVdId = mVd.getDisplay().getDisplayId();
                // 点亮虚拟屏，保证可接收输入/渲染
                try {
                    Class<?> g = Class.forName("android.hardware.display.DisplayManagerGlobal");
                    Object inst = g.getMethod("getInstance").invoke(null);
                    g.getMethod("requestDisplayPower", int.class, boolean.class)
                        .invoke(inst, mVdId, true);
                } catch (Throwable t) {
                    sb.append("power_err=").append(t).append('\n');
                }
                sb.append("vd_ok id=").append(mVdId).append(' ')
                  .append(w).append('x').append(h).append('\n');
            } else {
                sb.append("vd_exist id=").append(mVdId).append('\n');
            }

            sb.append("launch ").append(launchOnDisplay(mVdId)).append('\n');
            sb.append("pin ").append(ensureGameOnDisplay(mVdId, 6000)).append('\n');
            Thread.sleep(800);
            sb.append("launch2 ").append(launchOnDisplay(mVdId)).append('\n');
            sb.append("pin2 ").append(ensureGameOnDisplay(mVdId, 6000)).append('\n');

            // 窗口布局校验（只读探测 + 异常时尽力 resize，不改任何既有流程）：
            // pin ok ≠ 窗口铺满。size-compat 触发时固定 ROI 全错位，必须让日志可见。
            try {
                Thread.sleep(1200);  // pin ok 时 activity 可能还没挂窗口，等布局稳定
                String win = probeGameWindow();
                if (isWindowAbnormal(win)) {
                    forceTaskFullscreenBounds();
                    Thread.sleep(1200);
                    String win2 = probeGameWindow();
                    sb.append("win=").append(win2)
                      .append(" fix=").append(isWindowAbnormal(win2) ? "failed" : "resize-ok")
                      .append(" (was ").append(win).append(")\n");
                } else {
                    sb.append("win=").append(win).append('\n');
                }
            } catch (Throwable t) {
                sb.append("win=unknown probe_err:").append(t.getClass().getSimpleName()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("start_err=").append(t).append('\n');
        }
        return sb.toString();
    }

    /**
     * 【启动兜底】把游戏重新投到虚拟屏并确认落位（引擎 StartApp 回调路径，2026-09-28）。
     * 背景：StartApp 走到「进程不在」分支，多半是 ROM 游戏助手（荣耀/华为实测）杀掉了
     * 虚拟屏里的游戏实例——此时 App 侧的 monkey 兜底没有 display 概念，会把游戏直接
     * 开回物理主屏，用户看到「游戏跳出虚拟屏」。复用 launchOnDisplay（自带 force-stop
     * 冷启动，防半死 task 复用主屏落点）+ ensureGameOnDisplay（落点确认 + 漂移拉回）。
     */
    @Override
    public synchronized String relaunchGameOnVd() {
        if (mVdId < 0) return "vd off";
        StringBuilder sb = new StringBuilder();
        sb.append("launch=").append(launchOnDisplay(mVdId)).append(' ');
        sb.append("pin=").append(ensureGameOnDisplay(mVdId, 6000));
        return sb.toString();
    }

    /** 用 ActivityOptions.launchDisplayId + IActivityManager.startActivityAsUser 把游戏投到虚拟屏 */
    private String launchOnDisplay(int displayId) {
        StringBuilder sb = new StringBuilder();
        try {
            runCmd("/system/bin/am", "force-stop", GAME_PKG);
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(GAME_PKG, MaaConst.GAME_ACT_CLS);
            // EXCLUDE_FROM_RECENTS：游戏只能活在虚拟屏里，最近任务(后台)不显示它（仿 MAA-Meow）
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

            android.app.ActivityOptions opt = android.app.ActivityOptions.makeBasic();
            if (displayId != android.view.Display.DEFAULT_DISPLAY) {
                opt.setLaunchDisplayId(displayId);
            }
            android.os.Bundle bOptions = opt.toBundle();

            Object am = getActivityManager();
            if (am == null) {
                sb.append("am=null");
                return sb.toString();
            }
            Class<?> iAppThread = Class.forName("android.app.IApplicationThread");
            Class<?> profiler = Class.forName("android.app.ProfilerInfo");
            java.lang.reflect.Method m = null;
            for (java.lang.reflect.Method mm : am.getClass().getMethods()) {
                if (mm.getName().equals("startActivityAsUser")
                        && mm.getParameterTypes().length == 11) {
                    m = mm;
                    break;
                }
            }
            if (m == null) {
                m = am.getClass().getMethod("startActivityAsUser", iAppThread, String.class,
                    android.content.Intent.class, String.class, android.os.IBinder.class, String.class,
                    int.class, int.class, profiler, android.os.Bundle.class, int.class);
            }
            Object ret = m.invoke(am, null, "com.android.shell", intent, null, null, null, 0, 0, null, bOptions, -2);
            sb.append("startActivityAsUser=").append(ret);
        } catch (Throwable t) {
            sb.append("launch_err=").append(t);
        }
        return sb.toString();
    }

    private Object getActivityManager() {
        try {
            Class<?> amn = Class.forName("android.app.ActivityManagerNative");
            return amn.getDeclaredMethod("getDefault").invoke(null);
        } catch (Throwable t1) {
            try {
                Class<?> ats = Class.forName("android.app.ActivityTaskManager");
                return ats.getMethod("getService").invoke(null);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    // ============ 仿 MAA-Meow ensureAppOnDisplay：启动后校验任务落在虚拟屏，漂移拉回 ============

    /**
     * 轮询游戏任务是否落在 [displayId] 上。
     * B 服 U8 SDK 启动常二段跳，第一跳可能丢 launchDisplayId、任务漂回主屏(recents 可见)；
     * 发现漂移立即尝试拉回一次。-1(无法判断)宽松返回 ok，避免误报。
     */
    private String ensureGameOnDisplay(int displayId, long timeoutMs) {
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        String last = "no-task";
        boolean pulled = false;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            int d = getGameTaskDisplayId();
            if (d == -1) return "unk";
            if (d == -2) {
                last = "no-task";
            } else if (d == displayId) {
                return "ok";
            } else {
                last = "drift:" + d;
                if (!pulled) {
                    pulled = true;
                    if (!moveGameTaskToDisplay(displayId)) {
                        // 拉回失败：再补一发投屏启动，让系统 reparent 现有任务
                        launchOnDisplay(displayId);
                    }
                }
                return "drift:" + d + " pulled=" + pulled;
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { break; }
        }
        return "timeout(" + last + ")";
    }

    /** 游戏最近任务所在的 displayId；-2=无任务，-1=无法判断 */
    private int getGameTaskDisplayId() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                new ShellCtx(mContext).getSystemService(android.content.Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                android.content.ComponentName top = t.topActivity;
                if (top != null && GAME_PKG.equals(top.getPackageName())) {
                    return readTaskDisplayId(t);
                }
            }
            return -2;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** RunningTaskInfo.displayId 是 @hide 字段（Q+），反射读取（涉及父类 TaskInfo） */
    private int readTaskDisplayId(android.app.ActivityManager.RunningTaskInfo t) {
        java.lang.Class<?> cls = t.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("displayId");
                f.setAccessible(true);
                return f.getInt(t);
            } catch (Throwable ignored) {
                cls = cls.getSuperclass();
            }
        }
        return -1;
    }

    /** RunningTaskInfo 任务 id：29+ 在 TaskInfo.taskId，28 为 RunningTaskInfo.id，版本差异用反射兼容 */
    private int readTaskId(android.app.ActivityManager.RunningTaskInfo t) {
        java.lang.Class<?> cls = t.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField("taskId");
                f.setAccessible(true);
                return f.getInt(t);
            } catch (Throwable ignored) {
                cls = cls.getSuperclass();
            }
        }
        try {
            java.lang.reflect.Field f = t.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return f.getInt(t);
        } catch (Throwable th) {
            return -1;
        }
    }

    /** 把游戏任务移到虚拟屏：moveRootTaskToDisplay(31+)/moveStackToDisplay(29/30)，am move-stack 兜底 */
    private boolean moveGameTaskToDisplay(int displayId) {
        try {
            Class<?> ats = Class.forName("android.app.ActivityTaskManager");
            Object svc = ats.getMethod("getService").invoke(null);
            android.app.ActivityManager am = (android.app.ActivityManager)
                new ShellCtx(mContext).getSystemService(android.content.Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
            int taskId = -1;
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                android.content.ComponentName top = t.topActivity;
                if (top != null && GAME_PKG.equals(top.getPackageName())) {
                    taskId = readTaskId(t);
                    break;
                }
            }
            if (taskId < 0) return false;

            String[] names = { "moveRootTaskToDisplay", "moveStackToDisplay" };
            for (String n : names) {
                try {
                    java.lang.reflect.Method m = svc.getClass().getMethod(n,
                        int.class, int.class);
                    m.invoke(svc, taskId, displayId);
                    return true;
                } catch (Throwable ignored) {
                }
            }
            // am 命令兜底
            runCmd("/system/bin/am", "display", "move-stack",
                String.valueOf(taskId), String.valueOf(displayId));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ============ 游戏窗口布局校验（size-compat 检测，2026-09-27）============
    // 现象：pin ok 只保证「任务在虚拟屏上」；部分 ROM 对声明不可调整大小的游戏触发
    // size-compat——窗口按兼容尺寸渲染再放大铺满（画面放大裁切）或单侧锚定（黑边），
    // 固定 ROI 全部错位、模板识别必然失败（用户可见症状：公告弹窗铺满整个预览）。

    /**
     * 探测游戏窗口在虚拟屏上的实际布局，返回可读串：
     * "1280x720"（满铺，正常）/ "912x513 COMPAT"（窗口尺寸异常，黑边/裁切型）/
     * "unknown COMPAT:…"（frame 没解析到但发现 compat 痕迹）/ "unknown"（该 ROM 的
     * dumpsys 格式解析不了，不妄下结论）。
     */
    private String probeGameWindow() {
        try {
            String dump = runCmd("/system/bin/dumpsys", "window", "displays");
            if (dump == null || dump.length() < 200) {
                dump = runCmd("/system/bin/dumpsys", "window");
            }
            int[] f = (dump != null && dump.length() > 200) ? findGameWindowFrame(dump) : null;
            if (f != null) {
                int w = f[2] - f[0], h = f[3] - f[1];
                // 容差 6%：框架栏/inset 类的小偏差不算异常
                if (Math.abs(w - VD_W) <= VD_W * 6 / 100 && Math.abs(h - VD_H) <= VD_H * 6 / 100) {
                    return w + "x" + h;
                }
                return w + "x" + h + " COMPAT";
            }
            String hint = findCompatHint(
                runCmd("/system/bin/dumpsys", "activity", "activities"));
            if (hint != null) return "unknown " + hint;
            return "unknown";
        } catch (Throwable t) {
            return "unknown probe_err:" + t.getClass().getSimpleName();
        }
    }

    /** 游戏窗口是否异常（size-compat / 不满铺）。unknown 不算异常，避免误伤。 */
    private static boolean isWindowAbnormal(String win) {
        return win != null && win.contains("COMPAT");
    }

    /**
     * 在 dumpsys window 输出里找游戏 task/窗口的边界，返回 {x1,y1,x2,y2}；找不到 null。
     * 形态一（Android 13+ 实测 ColorOS14）：游戏 Task 行的下一行是 bounds=[x,y][w,h]：
     *   * Task{... A=10208:com.cipaishe.wuhua.bilibili ... mode=fullscreen ...}
     *     bounds=[0,0][1280,720]
     *   注意 mPreferredTopFocusableRootTask=Task{...} 这类引用行也含包名，但它们的
     *   后续行没有行首 bounds=，逐行尝试即可天然跳过；overrideConfig 里的
     *   mBounds=Rect(0,0-0,0) 占位值是 Rect 格式，不会撞 bounds= 模式。
     * 形态二（旧版）：WindowState 块标题行（行首 Window #N / mSurfaceWindow{，排除
     *   mCurrentFocus= 这类行内引用）块内的 frame=[x,y][w,h] / Rect(x, y - w, h)。
     */
    private int[] findGameWindowFrame(String dump) {
        try {
            String[] lines = dump.split("\n");
            java.util.regex.Pattern boundsAny = java.util.regex.Pattern.compile(
                "bounds=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]");
            java.util.regex.Pattern boundsLineStart = java.util.regex.Pattern.compile(
                "^\\s*bounds=\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains("Task{") || !lines[i].contains(GAME_PKG)) continue;
                for (int j = i; j < lines.length && j <= i + 3; j++) {
                    java.util.regex.Matcher m = (j == i ? boundsAny : boundsLineStart).matcher(lines[j]);
                    if (m.find()) {
                        return new int[] {
                            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)) };
                    }
                }
            }
            java.util.regex.Pattern frameBr = java.util.regex.Pattern.compile(
                "frame[^\\n]*?\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]\\[(-?\\d+)\\s*,\\s*(-?\\d+)\\]");
            java.util.regex.Pattern rect = java.util.regex.Pattern.compile(
                "Rect\\((-?\\d+),\\s*(-?\\d+)\\s*-\\s*(-?\\d+),\\s*(-?\\d+)\\)");
            int anchor = -1;
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if (lines[i].contains(GAME_PKG)
                        && (t.startsWith("Window #") || lines[i].contains("mSurfaceWindow{"))) {
                    anchor = i;
                    break;
                }
            }
            if (anchor < 0) return null;
            for (int i = anchor; i < lines.length && i < anchor + 60; i++) {
                String ln = lines[i];
                // 走进下一个窗口块还没见到 frame → 放弃（块内行数不同 ROM 差异大，60 行封顶）
                if (i > anchor && (ln.contains("Window{") || ln.contains("mSurfaceWindow{"))) break;
                java.util.regex.Matcher m = frameBr.matcher(ln);
                if (!m.find()) m = rect.matcher(ln);
                if (m.find()) {
                    return new int[] {
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)) };
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * dumpsys activity activities 里游戏 task 附近的 compat 缩放痕迹
     * （sizeCompat/compatScale 字样，限游戏包名行后 12 行内，避免抓到别的应用）。
     * 返回如 "COMPAT:sizeCompatScale=1.45"；无痕迹 null。
     */
    private String findCompatHint(String acts) {
        try {
            if (acts == null || acts.isEmpty()) return null;
            String[] lines = acts.split("\n");
            int near = -1000;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(GAME_PKG)) near = i;
                String low = lines[i].toLowerCase();
                if ((low.contains("sizecompat") || low.contains("size-compat") || low.contains("compatscale"))
                        && i - near >= 0 && i - near < 12) {
                    String s = lines[i].trim();
                    return "COMPAT:" + s.substring(0, Math.min(s.length(), 80));
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 尽力把游戏任务边界拉回满屏 1280x720。对 size-compat 任务 ROM 可能直接拒绝
     * （不可 resize 的 app resizeTask 被忽略），尽力而为：成功与否由调用方复查决定。
     */
    private boolean forceTaskFullscreenBounds() {
        try {
            int taskId = findGameTaskId();
            if (taskId < 0) return false;
            Class<?> ats = Class.forName("android.app.ActivityTaskManager");
            Object svc = ats.getMethod("getService").invoke(null);
            try {
                svc.getClass().getMethod("resizeTask", int.class, android.graphics.Rect.class)
                    .invoke(svc, taskId, new android.graphics.Rect(0, 0, VD_W, VD_H));
                return true;
            } catch (Throwable ignored) {
            }
            svc.getClass().getMethod("resizeTask", int.class, int.class, int.class, int.class, int.class)
                .invoke(svc, taskId, 0, 0, VD_W, VD_H);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 游戏 top task 的 taskId（readTaskId 兼容 28/29+ 字段差异）；无任务 -1 */
    private int findGameTaskId() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                new ShellCtx(mContext).getSystemService(android.content.Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(100);
            for (android.app.ActivityManager.RunningTaskInfo t : tasks) {
                android.content.ComponentName top = t.topActivity;
                if (top != null && GAME_PKG.equals(top.getPackageName())) {
                    return readTaskId(t);
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    @Override
    public byte[] grabVirtualFrame() {
        // 从帧缓存出图（缓存由预览回调线程维护，见 mOnFrame 注释）——绝不在这里
        // acquireLatestImage：帧池被预览持续消费后静止画面会拿不到帧（首装实测的教训）
        synchronized (mFrameLock) {
            if (!mFrameHas || mLastFrame == null) return new byte[0];
            try {
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(mFrameW, mFrameH,
                        android.graphics.Bitmap.Config.ARGB_8888);
                java.nio.ByteBuffer wrap = java.nio.ByteBuffer.wrap(mLastFrame);
                bmp.copyPixelsFromBuffer(wrap);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
                bmp.recycle();
                return out.toByteArray();
            } catch (Throwable t) {
                return new byte[0];
            }
        }
    }

    @Override
    public void stopVirtual() {
        try {
            mPreviewOn = false;
            if (sNativeOk) nvDetachSurface();
            synchronized (mFrameLock) {
                mFrameHas = false;   // 虚拟屏没了，旧帧作废（下次 startVirtualGame 重新积累）
            }
            if (mVd != null) { mVd.release(); mVd = null; }
            if (mReader != null) { mReader.close(); mReader = null; }
            mVdId = -1;
        } catch (Throwable ignored) {
        }
    }

    // ===== 预览直渲 AIDL（见类头「GPU 零拷贝直渲」注释） =====

    @Override
    public boolean setPreviewSurface(android.view.Surface surface) {
        if (surface == null || !surface.isValid()) return false;
        if (!ensureNative()) return false;
        try {
            boolean ok = nvAttachSurface(surface);
            mPreviewOn = ok;
            // 挂载后渲染线程会自动重画留存的最后一帧（静止画面也能立刻出图+计数+1），
            // 无需额外补画逻辑
            android.util.Log.i("MaaWH", "setPreviewSurface ok=" + ok);
            return ok;
        } catch (Throwable t) {
            android.util.Log.w("MaaWH", "attach preview surface err", t);
            mPreviewOn = false;
            return false;
        }
    }

    @Override
    public void releasePreviewSurface() {
        mPreviewOn = false;
        if (sNativeOk) {
            try {
                nvDetachSurface();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public long previewFrameCount() {
        if (!sNativeOk) return 0;
        try {
            return nvFrameCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public long previewConsumedCount() {
        return mConsumedCount;
    }

    @Override
    public String injectTapVD(int x, int y) {
        try {
            if (mVdId < 0) return "vd off";
            android.hardware.input.InputManager im =
                (android.hardware.input.InputManager) new ShellCtx(mContext)
                    .getSystemService(Context.INPUT_SERVICE);
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = buildTouchEvent(now, now,
                android.view.MotionEvent.ACTION_DOWN, x, y);
            android.view.MotionEvent up = buildTouchEvent(now, now + 50,
                android.view.MotionEvent.ACTION_UP, x, y);
            boolean sd = setEventDisplay(down, mVdId);
            boolean su = setEventDisplay(up, mVdId);
            // 实测：本环境(realme/Shizuku UserService) DOWN 用 ASYNC(0) 才能被消费，
            // WAIT_FOR_FINISH(2) 虽返回 true 但事件不生效
            boolean r1 = injectInput(down, 0);
            Thread.sleep(40);
            boolean r2 = injectInput(up, 0);
            down.recycle();
            up.recycle();
            return "tap " + x + "," + y + " dispSet=" + sd + "/" + su
                + " down=" + r1 + " up=" + r2;
        } catch (Throwable t) {
            return "inject_err=" + t;
        }
    }

    @Override
    public boolean isVdAlive() {
        try {
            return mVd != null && mReader != null && mVd.getDisplay() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int getStreamVolume(int stream) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                new ShellCtx(mContext).getSystemService(Context.AUDIO_SERVICE);
            return am.getStreamVolume(stream);
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public void setStreamVolume(int stream, int index) {
        try {
            android.media.AudioManager am = (android.media.AudioManager)
                new ShellCtx(mContext).getSystemService(Context.AUDIO_SERVICE);
            am.setStreamVolume(stream, index, 0);
        } catch (Throwable ignored) {
        }
    }

    // ============ 流式触摸注入（实时手势，仿 maameow） ============
    private long touchDownTime = 0;

    @Override
    public void touchDown(int x, int y) {
        try {
            if (mVdId < 0) return;
            touchDownTime = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent ev = buildTouchEvent(touchDownTime, touchDownTime,
                android.view.MotionEvent.ACTION_DOWN, x, y);
            setEventDisplay(ev, mVdId);
            injectInput(ev, 0);
            ev.recycle();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void touchMove(int x, int y) {
        try {
            if (mVdId < 0) return;
            long t = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent ev = buildTouchEvent(touchDownTime, t,
                android.view.MotionEvent.ACTION_MOVE, x, y);
            setEventDisplay(ev, mVdId);
            injectInput(ev, 0);
            ev.recycle();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void touchUp(int x, int y) {
        try {
            if (mVdId < 0) return;
            long t = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent ev = buildTouchEvent(touchDownTime, t,
                android.view.MotionEvent.ACTION_UP, x, y);
            setEventDisplay(ev, mVdId);
            injectInput(ev, 0);
            ev.recycle();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String injectSwipeVD(int x1, int y1, int x2, int y2, int duration) {
        try {
            if (mVdId < 0) return "vd off";
            android.hardware.input.InputManager im =
                (android.hardware.input.InputManager) new ShellCtx(mContext)
                    .getSystemService(Context.INPUT_SERVICE);
            long start = android.os.SystemClock.uptimeMillis();
            // 步数 = duration/16ms，上限 60（原实现 min(duration,60)/16 恒为 3，拖动判定不认）
            int steps = Math.max(4, Math.min(duration / 16, 60));
            // 手势事件按 maameow 构造：PointerProperties(TOOL_TYPE_FINGER) + PointerCoords(pressure/size)
            android.view.MotionEvent.PointerProperties props = new android.view.MotionEvent.PointerProperties();
            props.id = 0;
            props.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
            android.view.MotionEvent.PointerCoords coord = new android.view.MotionEvent.PointerCoords();
            for (int i = 0; i <= steps; i++) {
                long t = start + (long) duration * i / steps;
                int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN
                    : (i == steps ? android.view.MotionEvent.ACTION_UP
                        : android.view.MotionEvent.ACTION_MOVE);
                float x = x1 + (x2 - x1) * (float) i / steps;
                float y = y1 + (y2 - y1) * (float) i / steps;
                coord.x = Math.max(0f, x);
                coord.y = Math.max(0f, y);
                coord.pressure = (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) ? 0f : 1.0f;
                coord.size = 1.0f;
                android.view.MotionEvent ev = android.view.MotionEvent.obtain(
                    start, t, action, 1,
                    new android.view.MotionEvent.PointerProperties[] { props },
                    new android.view.MotionEvent.PointerCoords[] { coord },
                    0, 0, 1.0f, 1.0f, 0, 0,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
                setEventDisplay(ev, mVdId);
                // DOWN 用 ASYNC(0)：与点击同路径（本环境下 WAIT_FOR_FINISH 注入不被消费）
                boolean r = injectInput(ev, 0);
                ev.recycle();
                if (!r && i == 0) return "swipe down_rejected";
                if (i < steps) Thread.sleep(Math.max(1, duration / steps));
            }
            return "swipe " + x1 + "," + y1 + "->" + x2 + "," + y2 + " dur=" + duration;
        } catch (Throwable t) {
            return "inject_err=" + t;
        }
    }

    /** 构造单指触摸事件（6 参构造：最后一参为 metaState；单指构造 pressure/size 默认 1.0） */
    private android.view.MotionEvent buildTouchEvent(long downTime, long eventTime, int action, float x, float y) {
        android.view.MotionEvent ev = android.view.MotionEvent.obtain(
            downTime, eventTime, action, Math.max(0f, x), Math.max(0f, y), 0);
        ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        return ev;
    }

    /** 注入触摸事件；mode: 0=ASYNC 1=WAIT_FOR_RESULT 2=WAIT_FOR_FINISH */
    private boolean injectInput(android.view.InputEvent ev, int mode) {
        try {
            android.hardware.input.InputManager im =
                (android.hardware.input.InputManager) new ShellCtx(mContext)
                    .getSystemService(Context.INPUT_SERVICE);
            return (boolean) android.hardware.input.InputManager.class
                .getMethod("injectInputEvent", android.view.InputEvent.class, int.class)
                .invoke(im, ev, mode);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean setEventDisplay(android.view.InputEvent ev, int displayId) {
        try {
            android.view.InputEvent.class.getMethod("setDisplayId", int.class).invoke(ev, displayId);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String runCmd(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            int code = p.waitFor();
            return "exit=" + code + " out=" + new String(out).trim()
                + (err.length == 0 ? "" : " err=" + new String(err).trim());
        } catch (Throwable t) {
            return "runErr=" + t;
        }
    }

    /** 为 createVirtual* 常见签名拼参数：(String,int,int,int) / (int,int,int) 等 */
    private static Object[] buildArgs(Class<?>[] pt) {
        if (pt.length == 4 && pt[0].equals(String.class)
            && pt[1] == Integer.TYPE && pt[2] == Integer.TYPE && pt[3] == Integer.TYPE) {
            return new Object[] { "MaaWH-vd", 1080, 480, 160 };
        }
        if (pt.length == 3 && pt[0] == Integer.TYPE && pt[1] == Integer.TYPE && pt[2] == Integer.TYPE) {
            return new Object[] { 1080, 480, 160 };
        }
        if (pt.length == 5 && pt[0].equals(String.class)
            && pt[1] == Integer.TYPE && pt[2] == Integer.TYPE && pt[3] == Integer.TYPE) {
            return new Object[] { "MaaWH-vd", 1080, 480, 160, 0 };
        }
        return null;
    }

    /** 无路径的命令补 /system/bin 前缀（shell 进程 PATH 可能不完整）。 */
    private static String[] resolve(String[] cmd) {
        String[] out = cmd.clone();
        if (!cmd[0].contains("/")) {
            out[0] = "/system/bin/" + cmd[0];
        }
        return out;
    }
}
