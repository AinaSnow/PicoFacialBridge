# OSC diagnostics / 日志定位

Available in **0.6.0-osc.2** and later. Main logcat tag: **PicoFacialBridge**.
JNI/Binder's existing auxiliary tag is **PicoBinderProbe**.

在应用点击“显示诊断日志”，可以查看最近 160 行。每行最多约 2 KiB，进程退出后应用内历史不保留。
默认每 5 秒一条 DIAG 汇总；HEALTH 状态切换最多每秒一条；同类异常最多每 5 秒打印一次，
被限频的异常仍计数。启动、停止、设置应用、会话重连和模式变化按事件记录。
FINAL DIAG 是停止时不足 5 秒的最后统计窗口，不能当作长期发送频率。

若用户已经有可用且授权的 ADB，可自行运行（本任务没有连接或安装头显，也不修改调试设置）：

```sh
adb logcat -v threadtime -s PicoFacialBridge:I PicoBinderProbe:I '*:S'
```

抓取 Android 当前日志缓冲区的快照到电脑当前目录（一次性、有界，不是持续增长文件）：

```sh
adb logcat -d -v threadtime -s PicoFacialBridge:I PicoBinderProbe:I '*:S' > pico-osc-log.txt
```

不要用全量系统 logcat 代替上面的 tag 过滤。发送给他人前，检查并删去目标 IP、设备/账户信息和
系统错误中的本地路径。程序不创建日志文件、不采集摄像头图像、不保存表情数值，也不自动上传日志。
此次交付的附件只有 APK 与说明，不含用户运行日志。

## 如何判断

| 现象 | 日志证据及含义 |
| --- | --- |
| 未采集到数据 | `ringReadHzE/F=0.0/0.0`、`morphology=waiting` 或 `noNewFor1s`；看权限、佩戴/唤醒及 `sessionOpen` 异常 |
| 读取到帧但算法认为无效 | `ringReadHz` 非零而 `validGroupHzL/R/M` 为零，`invalidGroups` 增加；`latestGroupUsable=0` |
| 眼或嘴只保留缓存 | 对应 `validGroupHz` 为零，`validAgeMs` 上升；不足 2 秒仍为 `fresh`，超过后 `stale` 并归零 |
| 转换、发送都在运行 | `snapshotHz`、`udpSendOkHz` 非零；结合各组有效率看实际内容是否有新样本 |
| 只有发送，没有新采集 | `udpSendOkHz` 非零但 `ringReadHz`/`validGroupHz` 为零。这是发送缓存或中性快照，不代表追踪正常 |
| 网络发送失败 | `ERROR kind=udpSend`、`errorsTotalOpen/Read/Udp` 最后一项增加、`udp=sendError`；恢复为 `noSendError` |
| 收到帧但部分字段异常 | `nonFiniteFieldReadsZeroed` 或 `sourceFieldReadsOutside01` 增加；不打印具体值 |
| 追不上输入/历史被覆盖 | 看 `supersededBeforeSnapshot` 和 `ringOverrunsTotal`，两者都不是 UDP 丢包率 |
| VRChat 不动但发送正常 | UDP 不提供接收确认。检查电脑 IP/端口、VRChat OSC 和原生 Face Tracking Avatar 配置 |

`HEALTH` 会记录无新数据、缓存失效/恢复、有效性变化和发送错误/恢复。快速抖动会合并成最新状态；
`transitions` 计数包含被合并的变化。`latestGroupUsableL/R/M`：-1 表示当前缓存会话未见该组，
0 是最新已接受新时间戳的源有效标记不可用，1 是可用；可用组中个别 NaN/Inf 字段仍会被置零。

## 字段定义（避免误读）

- `windowSec`：真实单调时钟统计窗口秒数，所有 Hz 用计数除以此时间。
- `target`、`configuredMaxHz`、`mask`：当前 worker 生效的目标/频率上限/通道选择（眼=1，嘴=2）。
  `CONFIG_SAVED` 是已保存待重启；`BEGIN` / `CONFIG_APPLIED` 才表示当前 worker 应用的配置。
- `enhanced`：增强插件检测；`source`：实际解析的 `normal-morphology` / `EPI1-v1` / `EPI1-v2` / `none`。
- `ringReadHzE/F`：完整成功读取批次返回的 native eye / morphology 样本数每秒，**不是摄像头帧率**。
  全部通道关闭时不读 ring；`emptyReadBatchesE/F` 是读到空列表的批次数，不是丢帧数。
- `validGroupHzL/R/M`：新鲜、新时间戳且源有效标记可用的左眼/右眼/嘴组观察数每秒；
  普通模式一帧同时更新三组。该计数反映源采集，包含关闭输出的组，**不是各组独立 UDP 包数**。
- `invalidGroupsL/R/M`：新鲜新时间戳但有效标记不可用的组次数（本窗口）。不直接清空最后有效缓存。
- `validAgeMsL/R/M`：当前缓存各组最后有效源时间戳的年龄；-1 表示没有有效缓存。
  原采集允许的最多 100 ms 未来时钟容差显示为年龄 0。每次会话/模式/开关重置后重新累计。
- `lastReadAgeMsE/F`：最后一次非空成功读取批次到现在的年龄，-1 表示本 worker 尚未读到。
- `snapshotHz`：转换完整快照的次数每秒（不含单独中性清空包），可能包括后来失败/取消的发送尝试。
- `udpSendOkHz`：`DatagramSocket.send` 未抛异常的次数每秒，含中性清空包。
  `udpOkTotal` 是 worker 累计，`neutralOk` 是窗口内中性包数。**这些都不证明接收端收到。**
- `supersededBeforeSnapshot`：两次转换之间，同组有效观察中被更新观察替代的次数（本窗口）；
  这是主动合并最新状态，不是推测丢帧。`ringOverrunsTotal` 是 ring reader 检测历史断档的累计事件数，
  **不是丢失样本数**。没有网络丢包统计，因为协议没有回执。
- `duplicateGroups`：更新位已设置但时间戳不前进的组次数；`clockRejectedGroups`：新时间戳超出新鲜度/未来容差的组次数。
- `malformedFrames`：null 或不足 384 字节的 morphology 输入数。
- `nonFiniteFieldReadsZeroed` / `sourceFieldReadsOutside01`：有效组中处理字段读取时遇到非有限值/范围外数值的次数。
  普通模式共用眉毛会被两眼各读一次；后者不是输出错误次数（转换器仍会 clamp）。
- `errorsTotalOpen/Read/Udp`：worker 累计会话打开/读取 ring/UDP 发送异常次数；`suppressedSinceLog` 是该类上次日志后被限频的次数。
- `reconnects`：本窗口因死 Binder/所需源长时间不新鲜而重连的次数；`cacheResets`：本窗口缓存 reset 调用次数。
- `tMs`：从 Android 启动以来的单调毫秒，用于关联生命周期；不含原始表情时间序列。

## 验证边界

Host tests verify actual elapsed-time rates, per-group counters, invalid/missing/stale states,
coalescing, reset windows, error counts despite throttling, health recovery and absence of
expression samples in default summaries. These do not replace hardware validation.
The APK has not been installed or measured on a headset by this task.


## Eye direction check (0.6.0-osc.3)

“眼方向诊断 / Eye direction diagnostics” 默认关闭，仅保存在进程内；开启/关闭无需重启服务。
最多每 500 ms 一行 `EYE_LOOK`，关闭立即停止数值输出，退出应用进程后重置关闭。
日志仍只有内存内最近 160 行及 Android logcat 环形缓冲，不写文件、不自动上传。

- `usedRaw[45,11,35,12,44,2,31,0]`：本次映射实际使用的右外/内/上/下、左外/内/上/下源槽。
  这是采纳后的缓存（非有限值已归零）；无效眼观测可沿用旧有效值，过期或关闭发送后归零。
  它不是每个原始帧的未处理数据，不含眼睛图像。
- `blob0to7`：此次完整快照编码的 8 个方向字节，0..255，顺序右外/内/上/下、左外/内/上/下。
  这是本地构造的数据，不是 VRChat 接收确认。
- `latestUsableL/R`：最新接受观测的有效性，-1 未观测，0 无效，1 有效。
- `observationAgeMsL/R` 与 `validAgeMsL/R`：最新观测与最后有效源的年龄，未知为 -1。
  二者不同可识别“源继续更新但无效、正在保持旧眼状态”。

测试时保持头部不动，先只眨眼，再依次看中间、左、右、上、下、左上、右下，每步约 3 秒。
同时观察镜中瞳孔相对眼角的位置和用户所指的注视点/射线。斜向应能同时改变水平与垂直方向。
本版不改变方向符号、增益，也不额外发送旧 eye OSC。是否修复用户的斜视/射线表现仍待上述实测。

osc.3 更新：明确无效的嘴观测立即中性，缺失的新组仍保留；`mouthOutput=neutralInvalid`
明确标记该状态。`cacheL/R/M` 及 `validAgeMs` 仍描述最后有效样本缓存年龄，不代表无效嘴仍在输出旧值。
重连依据新鲜源观测是否继续推进，追踪质量无效本身不再触发重连。
`RECONNECT requiredSourceInactiveMs` 是“所需源最近不再满足新鲜条件”持续时间，不是有效表情年龄。
发送使用单调时钟剩余 deadline 等待，不提升 PICO morphology 源本身的频率。
