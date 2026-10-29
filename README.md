# FuckOopz

面向 **Oopz**（`com.weilaishanhai.oopz`）的 LSPosed 模块：**移除开屏广告**，并修复了oopz的一些神人问题

- 模块包名：`io.github.fuckoopz`（入口类 `io.github.fuckoopz.HookEntry`）
- 框架：libxposed API 102（LSPosed 2.x / 现代 NPatch）
- 目标版本：Oopz 1.1.10（反编译并实测于 Android 16 / arm64-v8a）
- 作用域：仅 `com.weilaishanhai.oopz`

## Attention AI Generated ! ! !

本项目全程 **100% vibe coding** 构建，作者没有也不具备能力对AI生成的java及kt代码进行审查，使用时若出现问题请发Issue

## 做了什么

Oopz 的开屏广告走一条 CJ SDK 桥接链：Dart → `flutter_cjadsdk_plugin` → `cj.mobile.CJSplash`。本模块在该插件内部下了三个 hook，全部只作用于目标应用进程：

| Hook | 作用 |
| --- | --- |
| `CJSplash.showAd(Activity, ViewGroup)` | 不渲染广告视图，直接回调 `onClose()`，让插件移除全屏容器并通知 Dart 关闭开屏页 |
| `CJSplash.loadAd(...)` | 直接失败，连广告网络请求都不发，省掉一次冷启动 DNS/HTTP |
| `FlutterCjadsdkPlugin.onMethodCall("cjSdkSetup")` | 直接回 `setupSuccess`，跳过 CJ SDK 初始化 |

第三条是「卡启动页」的根治：

```
Dart 启动页 --等 setupSuccess--> 插件 --CJMobileAd.init--> 广告 SDK
                                   |-- 成功: 发 setupSuccess
                                   '-- 失败: 只发 setupFailed（Dart 不处理）
```

当广告域名被拦截（如 AdGuard）、或 SDK 初始化异常时，插件只发 `setupFailed`，而 Dart 启动页没有失败分支，于是界面永远停在品牌启动图——底下登录页其实已经建好（键盘会弹出）。模块直接替插件回答 `setupSuccess`，启动页正常进入登录/主界面，且完全不依赖任何广告域名。

## 安装

1. 从 Releases 下载 `FuckOopz-*.apk` 安装（或用 Actions 产物）。
2. 打开 LSPosed → 模块 → 启用 **FuckOopz**。
3. 进入模块详情，勾选作用域 **Oopz**（模块声明了静态作用域，列表里只有 Oopz，带「推荐应用」标记）。
4. 强行停止 Oopz 后重新启动。

启用后不需要任何配置。

## 验证

```bash
adb logcat -c
adb shell am force-stop com.weilaishanhai.oopz
adb shell am start -n com.weilaishanhai.oopz/.MainActivity
adb logcat -s FuckOopz
```

正常应看到：

```
I FuckOopz : loaded in com.weilaishanhai.oopz, api=102
I FuckOopz : target package com.weilaishanhai.oopz
I FuckOopz : hook ok: CJSplash.showAd -> onClose
I FuckOopz : hook ok: CJSplash.loadAd -> onError
I FuckOopz : hook ok: cjSdkSetup -> setupSuccess
```

正常使用时不会出现任何广告相关日志（广告 SDK 根本不会初始化）；把源码里的 `DEBUG` 设为 `true` 重新打包，就能看到每次启动时 `cjSdkSetup` 被拦截的明细。

## 构建

需要 JDK 17+、Android SDK（platform 33、build-tools 36.0.0）。本仓库未附带 Gradle Wrapper，请使用 Gradle 8.14.3 或更新版本：

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

Windows 上在 `local.properties` 里写 `sdk.dir=D:/android-sdk` 亦可。

## 已知限制

- 只针对 **Oopz 1.1.10** 的类名/方法签名反编译结果编写。新版若改动 `flutter_cjadsdk_plugin` 或 `cj.mobile.CJSplash` 的结构，hook 会静默失败（日志里有 `hook failed`）。
- **不推荐用 NPatch 集成模式打包本模块**：NPatch 会给宿主重新签名，而 Oopz 的请求带自定义签名头（`Oopz-Signature`），换签名后登录（验证码换 token）会被服务端拒绝，表现为「输入验证码后卡住」，与本模块无关。LSPosed 方案不改宿主签名，无此问题。
- 不要在 system_server 或其他应用里启用。

## 参考

- [libxposed API](https://github.com/libxposed/api)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [NPatch](https://github.com/7723mod/NPatch)（仅作兼容性研究）

## License

[MIT](LICENSE)

---

## English

An LSPosed module for Oopz (`com.weilaishanhai.oopz`) built against libxposed API 102. It skips the cold-start splash ad by intercepting `CJSplash.showAd` / `CJSplash.loadAd`, and fixes the "stuck on splash screen forever" bug by answering the plugin's `cjSdkSetup` call with `setupSuccess` — otherwise Dart waits forever when the ad SDK fails to initialize. Tested with Oopz 1.1.10 on Android 16. Not recommended for NPatch integration mode: re-signing the host breaks Oopz's request signature and login.
