package io.github.fuckoopz;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * FuckOopz - removes the start-up splash ad of Oopz (com.weilaishanhai.oopz).
 *
 * <p>The module installs three hooks, only inside the target app process:</p>
 * <ol>
 *   <li>{@code CJSplash.showAd(Activity, ViewGroup)} - never render the ad view; report
 *       {@code onClose()} instead, which makes the plugin remove its full screen container and
 *       tells the Dart side the splash ad is gone.</li>
 *   <li>{@code CJSplash.loadAd(...)} - fail fast, so no ad network request is made at all.</li>
 *   <li>{@code FlutterCjadsdkPlugin.onMethodCall("cjSdkSetup")} - the Dart splash page waits for
 *       {@code setupSuccess}. When the CJ SDK cannot initialize (blocked network, no ads sold)
 *       the plugin only emits {@code setupFailed} and Dart ignores it, leaving the splash page
 *       on screen forever. Answer {@code setupSuccess} ourselves and skip the SDK init.</li>
 * </ol>
 *
 * <p>Built against libxposed API 102 (LSPosed 2.x, modern NPatch). Reverse engineered and tested
 * against Oopz 1.1.10.</p>
 */
public class HookEntry extends XposedModule {

    private static final String TAG = "FuckOopz";
    private static final String PKG = "com.weilaishanhai.oopz";
    private static final String CJS_PLUGIN =
            "com.example.flutter_cjadsdk_plugin.FlutterCjadsdkPlugin";

    /** Verbose per-call logging, useful while reverse engineering. */
    private static final boolean DEBUG = false;

    /** Unused fallback for the stuck splash issue, kept for reference only. */
    private static final boolean ENABLE_PREDRAW_FALLBACK = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam p) {
        log(Log.INFO, TAG, "loaded in " + p.getProcessName() + ", api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {
        if (!p.getPackageName().startsWith(PKG)) return;
        log(Log.INFO, TAG, "target package " + p.getPackageName());

        ClassLoader cl = p.getDefaultClassLoader();
        hookShowAd(cl);
        hookLoadAd(cl);
        hookSetupBridge(cl);

        if (ENABLE_PREDRAW_FALLBACK) hookPreDrawFallback(cl);
    }

    // ---------------------------------------------------------------- hooks

    /** Skip the ad view: the plugin only removes its splash container from {@code onClose}. */
    private void hookShowAd(ClassLoader cl) {
        try {
            Class<?> splash = Class.forName("cj.mobile.CJSplash", false, cl);
            Method showAd = splash.getDeclaredMethod("showAd", Activity.class, ViewGroup.class);
            hook(showAd).setId("oopz-skip-splash").intercept(chain -> {
                if (DEBUG) log(Log.INFO, TAG, "showAd intercepted");
                emitOnClose(splash, chain.getThisObject());
                return null;
            });
            log(Log.INFO, TAG, "hook ok: CJSplash.showAd -> onClose");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "showAd hook failed: " + Log.getStackTraceString(t));
        }
    }

    /** Kill the preload request, saving one network round trip during cold start. */
    private void hookLoadAd(ClassLoader cl) {
        try {
            Class<?> splash = Class.forName("cj.mobile.CJSplash", false, cl);
            Class<?> listenerCls = Class.forName("cj.mobile.listener.CJSplashListener", false, cl);
            Method loadAd = splash.getDeclaredMethod(
                    "loadAd", Context.class, String.class, int.class, int.class, listenerCls);
            hook(loadAd).setId("oopz-fastfail-splash-load").intercept(chain -> {
                if (DEBUG) log(Log.INFO, TAG, "loadAd fast-fail");
                emitOnError(chain.getArg(4));
                return null;
            });
            log(Log.INFO, TAG, "hook ok: CJSplash.loadAd -> onError");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "loadAd hook failed: " + Log.getStackTraceString(t));
        }
    }

    /**
     * Answer {@code cjSdkSetup} with {@code setupSuccess} on the platform channel, without ever
     * touching the ad SDK. This is what unsticks the splash page on devices where the ad
     * endpoints do not resolve.
     */
    private void hookSetupBridge(ClassLoader cl) {
        try {
            Class<?> plugin = Class.forName(CJS_PLUGIN, false, cl);

            Method onMethodCall = null;
            for (Method m : plugin.getDeclaredMethods()) {
                if ("onMethodCall".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    onMethodCall = m;
                    break;
                }
            }
            if (onMethodCall == null) throw new NoSuchMethodException("onMethodCall");

            final Class<?> callCls = onMethodCall.getParameterTypes()[0];
            final Field nameField = findMethodNameField(callCls);
            final Field channelField = findChannelField(plugin);
            if (nameField == null || channelField == null) {
                throw new IllegalStateException("cannot locate method name or MethodChannel");
            }
            final Method invoke = findChannelInvoke(channelField.getType());
            if (invoke == null) throw new NoSuchMethodException("MethodChannel.invokeMethod");

            hook(onMethodCall).setId("oopz-fix-setup").intercept(chain -> {
                Object call = chain.getArg(0);
                if (!"cjSdkSetup".equals(nameField.get(call))) return chain.proceed();

                if (DEBUG) log(Log.INFO, TAG, "cjSdkSetup intercepted, answering setupSuccess");
                final Object self = chain.getThisObject();
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        invoke.invoke(channelField.get(self), "setupSuccess", "ok");
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "reply setupSuccess failed: " + Log.getStackTraceString(t));
                    }
                });
                return null;
            });
            log(Log.INFO, TAG, "hook ok: cjSdkSetup -> setupSuccess");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "setup bridge hook failed: " + Log.getStackTraceString(t));
        }
    }

    /**
     * Not used in normal operation: FlutterActivityAndFragmentDelegate installs an OnPreDraw
     * listener that returns {@code isFlutterUiDisplayed}, so the window keeps cancelling draws
     * until the first Flutter frame arrives. Forcing {@code true} lets it draw earlier. The real
     * fix for the stuck splash page is {@link #hookSetupBridge(ClassLoader)}.
     */
    private void hookPreDrawFallback(ClassLoader cl) {
        try {
            Class<?> listener = Class.forName("io.flutter.embedding.android.f$b", false, cl);
            Method onPreDraw = listener.getDeclaredMethod("onPreDraw");
            hook(onPreDraw).setId("oopz-force-predraw").intercept(chain -> {
                try {
                    chain.proceed();
                } catch (Throwable ignored) {
                }
                return Boolean.TRUE;
            });
            log(Log.INFO, TAG, "hook ok: pre-draw force true");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "pre-draw hook failed: " + Log.getStackTraceString(t));
        }
    }

    // ---------------------------------------------------------- reflection

    private void emitOnClose(Class<?> splashCls, Object splash) {
        try {
            Field f = splashCls.getDeclaredField("listener");
            f.setAccessible(true);
            final Object listener = f.get(splash);
            if (listener == null) return;
            final Method onClose = listener.getClass().getMethod("onClose");
            onClose.setAccessible(true);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    onClose.invoke(listener);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "onClose failed: " + Log.getStackTraceString(t));
                }
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "emit onClose failed: " + Log.getStackTraceString(t));
        }
    }

    private void emitOnError(Object listener) {
        if (listener == null) return;
        try {
            Method onError = listener.getClass().getMethod("onError", String.class, String.class);
            onError.setAccessible(true);
            onError.invoke(listener, "skip", "skip");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "emit onError failed: " + Log.getStackTraceString(t));
        }
    }

    /**
     * {@code MethodCall}'s method name. The plugin keeps the field as {@code a}; fall back to the
     * first String field if a future build renames it.
     */
    private static Field findMethodNameField(Class<?> callCls) {
        try {
            Field f = callCls.getDeclaredField("a");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {
            return findStringField(callCls);
        }
    }

    private static Field findStringField(Class<?> cls) {
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /** The plugin's {@code MethodChannel} field, named "channel" or typed like one. */
    private static Field findChannelField(Class<?> plugin) throws NoSuchFieldException {
        try {
            Field f = plugin.getDeclaredField("channel");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {
            for (Field f : plugin.getDeclaredFields()) {
                if (findChannelInvoke(f.getType()) != null) {
                    f.setAccessible(true);
                    return f;
                }
            }
            throw new NoSuchFieldException("MethodChannel field");
        }
    }

    /** {@code MethodChannel.invokeMethod(String, Object)} returning void. */
    private static Method findChannelInvoke(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 2 && ps[0] == String.class && ps[1] == Object.class
                    && m.getReturnType() == void.class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
