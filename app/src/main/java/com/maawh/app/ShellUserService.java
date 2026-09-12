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

    private android.hardware.display.VirtualDisplay createVdProbe(StringBuilder sb) {
        try {
            // 用反射取真实 flag 常量，避免写错位数
            int flags = 0;
            String[] names = {
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
            for (String n : names) {
                try {
                    java.lang.reflect.Field f = android.hardware.display.DisplayManager.class.getField(n);
                    flags |= f.getInt(null);
                } catch (Throwable ignored) {
                }
            }
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

    // M4-④：虚拟屏必须与 whmx 模板/坐标基准帧同尺寸(1280x720 标准 16:9)，
    // 否则游戏画面布局(模板/固定坐标)与校准不一致，识别失效。
    private static final int VD_W = 1280;
    private static final int VD_H = 720;
    private static final int VD_DPI = 320;

    private static final String GAME_PKG = "com.cipaishe.wuhua.bilibili";
    private static final String GAME_ACT = "com.cipaishe.wuhua.bilibili/.activity.ONESDKLaunchActivity";

    @Override
    public synchronized String startVirtualGame() {
        StringBuilder sb = new StringBuilder();
        try {
            if (mVd == null) {
                int flags = 0;
                String[] names = {
                    "VIRTUAL_DISPLAY_FLAG_PUBLIC", "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
                    "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", "VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL",
                    "VIRTUAL_DISPLAY_FLAG_TRUSTED", "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
                    "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED",
                    "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", "VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP",
                    "VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED"
                };
                for (String n : names) {
                    try {
                        java.lang.reflect.Field f = android.hardware.display.DisplayManager.class.getField(n);
                        flags |= f.getInt(null);
                    } catch (Throwable ignored) {
                    }
                }
                int w = VD_W, h = VD_H, dpi = VD_DPI; // 1280x720：与 whmx 基准帧同尺寸
                android.media.ImageReader ir = android.media.ImageReader.newInstance(w, h,
                        android.graphics.PixelFormat.RGBA_8888, 3);
                java.lang.reflect.Constructor<android.hardware.display.DisplayManager> ctor =
                    android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
                ctor.setAccessible(true);
                android.hardware.display.DisplayManager dm = ctor.newInstance(new ShellCtx(mContext));
                mVd = dm.createVirtualDisplay("MaaWH-VD", w, h, dpi, ir.getSurface(), flags);
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
        } catch (Throwable t) {
            sb.append("start_err=").append(t).append('\n');
        }
        return sb.toString();
    }

    /** 用 ActivityOptions.launchDisplayId + IActivityManager.startActivityAsUser 把游戏投到虚拟屏 */
    private String launchOnDisplay(int displayId) {
        StringBuilder sb = new StringBuilder();
        try {
            runCmd("/system/bin/am", "force-stop", GAME_PKG);
            android.content.Intent intent = new android.content.Intent();
            intent.setClassName(GAME_PKG, "com.cipaishe.wuhua.bilibili.activity.ONESDKLaunchActivity");
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

    @Override
    public byte[] grabVirtualFrame() {
        android.media.ImageReader r = mReader;
        if (r == null) return new byte[0];
        android.media.Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return new byte[0];
            android.media.Image.Plane p = img.getPlanes()[0];
            java.nio.ByteBuffer buf = p.getBuffer();
            int w = img.getWidth();
            int h = img.getHeight();
            int ps = p.getPixelStride();
            int rs = p.getRowStride();
            int[] px = new int[w * h];
            byte[] row = new byte[rs];
                for (int y = 0; y < h; y++) {
                    buf.position(y * rs);
                    buf.get(row, 0, rs);
                    int base = y * w;
                    for (int x = 0; x < w; x++) {
                        int o = x * ps;
                        int red = row[o] & 0xff;
                        int g = row[o + 1] & 0xff;
                        int b = row[o + 2] & 0xff;
                        int a = row[o + 3] & 0xff;
                        px[base + x] = (a << 24) | (red << 16) | (g << 8) | b;
                    }
                }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(px, w, h,
                    android.graphics.Bitmap.Config.ARGB_8888);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
            bmp.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        } finally {
            if (img != null) img.close();
        }
    }

    @Override
    public void stopVirtual() {
        try {
            if (mVd != null) { mVd.release(); mVd = null; }
            if (mReader != null) { mReader.close(); mReader = null; }
            mVdId = -1;
        } catch (Throwable ignored) {
        }
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
