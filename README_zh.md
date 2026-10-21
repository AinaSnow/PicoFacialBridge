# PicoFacialBridge OSC

[English](README.md) | 基于 [WolalaQAQ/PicoFacialBridge](https://github.com/WolalaQAQ/PicoFacialBridge) 的独立 fork

本版本只在 PICO 头显内采集眼动／面部数据、转换为 Unified Expressions，直接通过 OSC
发送给运行 VRChat 的电脑。**不需要 PC 桥、daemon、VRCFaceTracking 或 UnifiedPicoModule。**
Virtual Desktop 继续负责串流。原来的 Binder/JNI 采集层保留，不占用 OpenXR session。

## 使用

1. 自行安装构建出的 `PicoFacialBridge-debug.apk`。应用名称 **PicoFacialBridge OSC**，
   包名 `dev.pico.facialbridge.osc`，与原版分开安装。使用前停止原版桥。
2. 在 PICO 系统中启用眼动和面部追踪。打开本应用，输入**运行 VRChat 的电脑 IPv4 地址**，
   UDP 端口默认 **9000**，发送频率默认 **60 Hz**，允许 30–120 Hz。
3. 点击启动，授予眼动和面部两项权限。目标 IP／端口／频率在启动或重启时应用；
   眼动和面部开关立即生效，全部关闭时暂停采集。
4. 使用支持新版 [OSC Face Tracking](https://vrc-beta-docs.netlify.app/avatars/face-tracking/osc/) 的 VRChat，
   启用 OSC。Avatar 需要配置原生 Face Tracking 并启用 **Use All Tracking Keys**。
   只有旧 VRCFT 自定义参数的 Avatar 不足以使用此协议。
5. 照常使用 VD 串流；桥的窗口可以关闭，前台服务继续工作。结束时通过界面或通知停止。

这里的 PC 就是运行 VRChat 的电脑，不是需要安装的新程序。**发包成功不等于 VRChat 已收到**：
UDP 没有确认机制。若发包数增长但人物不动，请检查 IP、端口、VRChat OSC、局域网可达性及 Avatar
原生面捕配置。应用不会更改防火墙或网络安全设置，也不再监听 9030 或进行订阅／发现握手。
只在可信局域网使用，OSC UDP 没有加密或认证。

## 数据处理

- 地址 `/tracking/face/v1`，类型 `,b`，blob 固定 102 字节；加上完整 OSC 头和 padding 后每包 **132 字节**。
- 依官方 wire 顺序映射，范围限制为 0..1，再乘 255 截断。NaN／Inf 置零，不支持的项保留零位。
- 视线先对相反方向系数做差再拆正负；眨眼取表情系数。默认 EyeWide 左右各 ×2，其他纠正不启用。
- 增强 EPI1 v2 按左眼／右眼／嘴独立时间戳更新缓存，一个组更新不会清空另一个组。
  每组超过 **2 秒**没有有效样本才归零；模式、会话、重启或开关变化会清缓存。
- 瞳孔四项（blob 10–13）维持零。毫米瞳孔数据缺少可靠归一化，本版本不宣称支持。
- 每个发送周期读取原有有界共享内存 ring，合并保留样本后发完整最新快照；不积压发送队列、不补发突发包。
  转换数组与 OSC buffer 复用，原采集 ring reader 仍有其原本的分配。
- 发送频率是上限目标，Android 调度或 Binder 调用可能降低实际频率；发送异常按 1 秒重试。
  保留权限、前台服务、wake lock、增强检测、休眠／死服务恢复及必要日志。停止时尝试发送一次中性包。

## 构建和验证范围

当前采集依据上游的 PICO 4 Pro／PICO OS 5.13.7（Android 10，arm64-v8a）实现。
**本 fork 尚未进行头显、VD 共存、VRChat Avatar 或长时性能实测**。上游的测试记录不等于本分支实测。

纯 Java 测试：`pwsh -File tests/run-direct-osc.ps1`；所有 host 回归：`bash tests/run-host-tests.sh`。
构建需要 JDK 17、Android SDK 35／build-tools 35.0.0、NDK 26.1.10909125、CMake 3.22.1：

```powershell
.\gradlew.bat --no-daemon :bridge:assembleDebug :bridge:lintDebug
python tests/test_apk_distribution.py
```

APK 在 `bridge/build/outputs/apk/debug/bridge-debug.apk`。不要安装 androidTest 测试 APK。
CI 也会验证签名、对齐与内容并保存 `android-build` 构建附件；不发布 Release。
调试签名可能随 CI 构建变化，更新时可能需要自行卸载旧测试版再装，设置会清除。

详见 [验证记录与待实测项目](docs/VALIDATION.md)、[MIT 许可](LICENSE) 和 [版权归属](THIRD_PARTY_NOTICES.md)。
旧 BridgeSplit 代码仅用于 probe／host 回归，已排除在本版本 APK 的 Java 源集之外，没有旧模式切换。
