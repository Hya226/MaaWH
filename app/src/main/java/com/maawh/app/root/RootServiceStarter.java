package com.maawh.app.root;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.system.Os;

import com.maawh.app.IUserService;
import com.maawh.app.IBinderRegister;
import com.maawh.app.ShellUserService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Root 模式的提权服务入口（app_process main，对标 MAA-Meow 的 RemoteServiceStarter）。
 *
 * 链路：su -c 拉起本进程 → 降权回 shell uid（2000，行为面与 Shizuku 服务完全对齐，
 * root 只用于启动这一刻）→ 反射构建包 Context（ActivityThread.systemMain →
 * SystemContext → createPackageContext → makeApplication，OEM 上 makeApplication
 * 失败则降级用 packageContext）→ 实例化 ShellUserService(Context)（与 Shizuku
 * v13 同款构造）→ bindService 回路把服务 binder 注册回 App 的
 * RootBinderHolderService——App 拿到的引用指向本进程，调用与 Shizuku 模式同构。
 *
 * 生命周期：App 死亡/解绑/onNullBinding → 统一退出口（先 destroy 服务再 halt，
 * 清理卡死也有 5s 硬期限，不留孤儿进程）。
 */
public final class RootServiceStarter {

    private static ShellUserService service;
    private static Context serviceContext;
    private static String packageName;
    private static ServiceConnection holderConnection;
    private static final AtomicBoolean exiting = new AtomicBoolean(false);
    private static volatile Thread exitOwner;

    private RootServiceStarter() {
    }

    public static void main(String[] args) {
        // 1) 降权回 shell：root 只用于启动；注入/命令行为面与 Shizuku 服务一致
        try {
            Os.setgid(2000);
            Os.setuid(2000);
        } catch (Throwable t) {
            System.err.println("[RootStarter] setuid(2000) failed: " + t);
        }
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        try {
            // 2) Context 反射构建
            String pkg = "com.maawh.app";
            for (String a : args) {
                if (a.startsWith("--package=")) pkg = a.substring(10);
            }
            packageName = pkg;
            serviceContext = buildPackageContext(pkg);
            System.err.println("[RootStarter] package context ready: " + pkg);

            // 3) 服务实例化（ShellUserService 的 Context 构造，与 Shizuku v13 同款）
            service = new ShellUserService(serviceContext);
            System.err.println("[RootStarter] ShellUserService created");
        } catch (Throwable tr) {
            System.err.println("[RootStarter] init failed: " + tr);
            Runtime.getRuntime().halt(1);
            return;
        }

        // 4) bindService 回路：把服务 binder 注册回 App
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, "com.maawh.app.root.RootBinderHolderService"));
        holderConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                System.err.println("[RootStarter] holder connected, registering service binder");
                try {
                    IBinderRegister reg = IBinderRegister.Stub.asInterface(binder);
                    if (reg == null) {
                        System.err.println("[RootStarter] register stub is null");
                        exitAll("register stub null");
                        return;
                    }
                    reg.registerServiceBinder(service);
                    System.err.println("[RootStarter] service binder registered");
                } catch (Throwable tr) {
                    System.err.println("[RootStarter] register failed: " + tr);
                    exitAll("register failed: " + tr);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                exitAll("holder disconnected");
            }

            @Override
            public void onBindingDied(ComponentName name) {
                exitAll("holder binding died");
            }

            @Override
            public void onNullBinding(ComponentName name) {
                exitAll("holder null binding");
            }
        };
        try {
            if (!serviceContext.bindService(intent, holderConnection, Context.BIND_AUTO_CREATE)) {
                System.err.println("[RootStarter] bindService returned false");
                Runtime.getRuntime().halt(1);
                return;
            }
        } catch (Throwable tr) {
            System.err.println("[RootStarter] bindService failed: " + tr);
            Runtime.getRuntime().halt(1);
            return;
        }

        Looper.loop();
        Runtime.getRuntime().halt(0);
    }

    /** Context 反射构建：systemMain → SystemContext → 包 Context →（尽力）Application */
    private static Context buildPackageContext(String pkg) throws Exception {
        Object activityThread = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("systemMain").invoke(null);
        Method getSystemContext = activityThread.getClass().getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context systemContext = (Context) getSystemContext.invoke(activityThread);

        try {
            Class.forName("android.ddm.DdmHandleAppName")
                    .getDeclaredMethod("setAppName", String.class, int.class)
                    .invoke(null, pkg + ":root_service", 0);
        } catch (Throwable ignored) {
        }

        int flags = Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY;
        Context pkgCtx = systemContext.createPackageContext(pkg, flags);
        try {
            Field f = pkgCtx.getClass().getDeclaredField("mPackageInfo");
            f.setAccessible(true);
            Object loadedApk = f.get(pkgCtx);
            Method makeApp = loadedApk.getClass()
                    .getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
            makeApp.setAccessible(true);
            Application app = (Application) makeApp.invoke(loadedApk, true, null);
            // 部分 OEM（如 MIUI）改了 makeApplication，shell 身份下初始化失败；
            // 失败时 packageContext 本身可用，直接降级（MAA-Meow 同款处理）
            return app != null ? app : pkgCtx;
        } catch (Throwable tr) {
            System.err.println("[RootStarter] makeApplication failed, fallback pkgCtx: " + tr);
            return pkgCtx;
        }
    }

    /** 统一退出口：首个调用者起 5s 硬期限、destroy 服务、halt；后来者直接返回 */
    private static void exitAll(String reason) {
        if (!exiting.compareAndSet(false, true)) {
            return;
        }
        exitOwner = Thread.currentThread();
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            System.err.println("[RootStarter] exit deadline hit (" + reason + "), halt");
            Runtime.getRuntime().halt(1);
        });
        killer.setDaemon(true);
        killer.start();
        System.err.println("[RootStarter] exiting: " + reason);
        try {
            if (service != null && service.asBinder().pingBinder()) {
                service.destroy();
            }
            if (serviceContext != null && holderConnection != null) {
                serviceContext.unbindService(holderConnection);
            }
        } catch (Throwable ignored) {
        }
        Runtime.getRuntime().halt(0);
    }
}
