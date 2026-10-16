# chroot 后端集成 — 阶段一交接文档

目标:在 DroidDeck 里新增一个 **chroot 容器后端**,仅在已 root 的设备上启用,走最窄路径
(Steam 下载游戏 + 运行)。桌面和 Flatpak 安装在 chroot 后端下禁用。proot 仍是默认。

同时解决用户此前用 chroot-distro 遇到的 **残存问题**(容器没关干净 → sudo 提权失败 /
挂载冲突,需手动 kill)——通过 root 级强制清理脚本,即使 app 进程被杀也能清干净。

---

## 一、本次会话已完成

### 1. 偏好开关 — `SessionPrefs.kt`
新增 proot/chroot 后端选择(默认 proot):

```kotlin
const val BACKEND_PROOT = "proot"
const val BACKEND_CHROOT = "chroot"

fun runtimeBackend(context: Context): String =
    prefs(context).getString("runtimeBackend", BACKEND_PROOT) ?: BACKEND_PROOT

fun setRuntimeBackend(context: Context, backend: String) {
    require(backend == BACKEND_PROOT || backend == BACKEND_CHROOT)
    prefs(context).edit().putString("runtimeBackend", backend).apply()
}
```
文件:`app/src/main/java/com/droiddeck/launcher/session/SessionPrefs.kt`(prootFastPath 块之后)

### 2. chroot 后端 — `ChrootRuntime.kt`(新建)
`app/src/main/java/com/droiddeck/launcher/runtime/ChrootRuntime.kt`

- `suBinary()`:遍历 Magisk/KSU 标准位置找 su(`/debug_ramdisk/su` 等)。
- `command(...)`:读取 asset 模板 `chroot-entry.sh`,填入 root/appPid/binds 列表,落盘成
  `cacheDir/chroot-entry-N.sh`,chmod 0755,返回 `["su","-c",scriptPath]`。
  - **复用 `LinuxRuntime.binds()`** 产出的 `host:guest` 规格,零改动地转成真 `mount --bind`。
  - guest 参数列表(即 `droiddeck-session` 那串)原样透传,chroot 对游戏透明。
- `stop(context)`:读取 `chroot-stop.sh`,以 root 强制清理(杀 rootfs 内所有进程 +
  全量卸载挂载)。**app(uid) 无法信号 root 进程,这是反残存的最终保证。**

### 3. 入口脚本 — `app/src/main/assets/chroot-entry.sh`
运行于 su 授权的 root 上下文,三层防残存:
1. `trap cleanup EXIT INT TERM HUP`:正常/信号退出时,杀所有 `/proc/PID/root == rootfs`
   的进程,按 binds **逆序** `umount -l`,再用 awk 扫 `/proc/mounts` 深度优先兜底。
2. **后台 watchdog**:每 5s 探活 app pid;app 死了自动 cleanup(app 被系统杀也能清)。
3. **fakeRoot 参数目前未启用**(guest 跑 root);接口已留,后续需要可加 `setpriv`。

### 4. 停止脚本 — `app/src/main/assets/chroot-stop.sh`
独立的强制清理脚本,`ChrootRuntime.stop` 用;幂等,可重复调用。

### 5. 会话集成 — `SessionService.runSession()`
`app/src/main/java/com/droiddeck/launcher/session/SessionService.kt`(约 393 行起)

- 按 `ChrootRuntime.backendEnabled` 二选一:
  - **chroot**:调 `ChrootRuntime.command(guest, fullBinds, false)`;不设任何 `PROOT_*`
    环境变量;不启用 ProotFastPath。
  - **proot**:保持原逻辑(`LinuxRuntime.command` + PROOT_LOADER + fastpath)。
- terminal 命令同样按后端分叉(chroot 侧 binds 传 null,搭已有 session 的挂载)。
- `stopSession` 的 teardown 线程末尾追加:
  `if (ChrootRuntime.backendEnabled(this)) ChrootRuntime.stop(this)`
  保证 root 进程/挂载被强制回收(普通 teardown 的 `killProcess` 对 root guest 无效)。

### 6. bwrap 禁用 — `App.kt` / `GuestCommand.kt` / `addClientEnvironment`
chroot 后端下不发 `FLATPAK_BWRAP` / `DROIDDECK_BWRAP_SOCKET`,不起 `BwrapSpawner`
(那条 ~400 行 bwrap 代理路径整条不进入执行)。
- `App.kt:24`:`BwrapSpawner.start` 仅 proot 后端调用。
- `GuestCommand.kt`:仅 proot 后端注入两个 bwrap 环境变量。
- `SessionService.addClientEnvironment`:同上。

### 7. UI 禁用入口
- `MainActivity.kt` `onDesktop`:chroot 下 toast `chroot_no_desktop`,不启动。
- `MainActivity.kt` `onFlatpakApp`:chroot 下 toast `chroot_no_flatpak`,不启动。
- `AgentStartActivity.kt`:chroot 下拒绝 desktop 模式请求,直接 finish。
- `UserApps.kt` `addFlatpak`/`addFlatpakBundle`:chroot 下返回错误串,不执行安装。

### 8. 字符串资源
`app/src/main/res/values/strings.xml`:
```xml
<string name="chroot_no_desktop">Desktop is disabled while the chroot runtime is in use.</string>
<string name="chroot_no_flatpak">Flatpak install is disabled while the chroot runtime is in use.</string>
```

---

## 二、未完成 / 待验证(下一步)

### A. 编译验证(本次因环境无 JDK/SDK 中断)
代码改动尚未编译。请在配置好环境后运行:

```bash
cd /home/liuw/droid/DroidDeck
# 只编译,不需要原生 proot 构建:
./gradlew -PskipRust=true :app:compileDebugSources
# 或跑单元测试:
./gradlew -PskipRust=true :app:testDebugUnitTest
```

可能存在的编译问题(需人工核对):
1. **`SessionService.runSession` 改动**用了 `fullBinds`(原先两次调 `LinuxRuntime.binds`
   合并成一次复用)。需确认 proot 路径行为与原来完全一致(参数传递顺序)。已尽量保持,
   但务必 diff 核对。
2. `GuestCommand.kt` 改动后 `listOf` 少了两个 bwrap 项,改为条件 `cmd.add`。确认 `cmd`
   是 ArrayList 且 `+= ` / `add` 都可用(原文件用 `cmd += listOf(...)`)。
3. `ChrootRuntime.kt` 用了 `android.system.Os.chmod`、`context.assets.open` — import 已写全。
4. `Os.chmod(script.path, 0b111101101)` — 0755 的二进制字面量,确认 Kotlin 接受。

### B. 开关入口（已完成 UI）
已在 Performance 对话框的「Session fixes」组顶部加了一个 **chroot runtime (root)** 开关
(commit `eca5c14e`)。开启时:
- 写入 `SessionPrefs.runtimeBackend = BACKEND_CHROOT`(关闭则回 `BACKEND_PROOT`)。
- 下方 proot 专用的 seccomp / fast-path 两个开关自动禁用(chroot 下 proot 不跑)。
- hint 文案提示:需 root、desktop 和 Flatpak 被禁用、下次会话生效。
`runtimeBackend` 也已加入 `SettingsReset` 的 SESSION_KEYS(重置设置会清掉)并在
`DeviceReport` 里报告一行(`runtime backend`),便于 logcat 诊断。改动文件:
`PerformanceDialog.kt` / `MainActivity.kt` / `strings.xml`(perf_chroot*, perf_fastpath_off_chroot) /
`SettingsReset.kt` / `DeviceReport.kt`。

### C. 设备实测要点
切到 chroot 后端后,在已 root 设备上验证:
1. **su 被找到**:`logcat | grep ChrootRuntime` 应见 `staged chroot entry ... su=/...`。
2. **挂载点齐**:`cat /proc/mounts | grep <rootfs>` 应见所有 binds。
3. **Steam 起来**:gamescope + steam 正常进入 Big Picture。
4. **关键残存测试**:
   - 正常退出 Steam → `stop` 跑完 → `cat /proc/mounts | grep linuxfs` 应**为空**。
   - 强杀 app(模拟系统回收)→ 等 ~5s → watchdog 应自动卸载并杀进程,同上为空。
   - 再次启动 session → 不应出现 mount 冲突 / sudo 失败等残存症状。
5. **游戏**:Steam 内启动一个游戏(Proton),确认进同一 chroot,自动隔离。

### D. 已知限制 / 后续工作
- **guest 跑 root**:Steam 数据文件会变成 root 拥有,app(普通 uid)的"重置/清理缓存"
  功能失效。后续要么给那些操作也加 su 通道,要么 guest 内 `setpriv` 降权到 app uid
  (但需处理 Xwayland setgid — proot 原来用 fake_id0 补丁解的)。
- **`--kernel-release` / kompat 伪装** chroot 无法拦截 uname;`droiddeck-session`
  脚本里的 `--kernel-release` 相关逻辑可能行为不同,需实测 Steam 是否受影响。
- **`/dev/shm`**:proot 用 cacheDir 软链,chroot 用真 bind,已由 `binds()` 处理。
- **rootfs 内仍需的假 proc/sys**(GPU sysfs 树、cpuinfo 等):`binds()` 已造好 host 侧
  文件,chroot 照挂即可;但若 root 下真 `/proc`/`/sys` 可读,部分假文件可移除(优化项,
  非阻塞)。
- **chroot 开关在无 root 时应置灰(待办)**:目前 Performance 对话框里的 `chroot runtime
  (root)` 开关在任何设备上都能点开,非 root 设备点开后下次会话会直接失败(找不到 su /
  su 提权失败)。应在 UI 侧根据设备是否真有 root 来决定开关可用性,而非让用户踩坑。

  **验收标准**:应用未被授予 root(su 找不到,或 su 能跑但 `id -u` ≠ 0)时,开关
  置灰、不可勾选;hint 文案换成“Needs root (a working su).”。反之正常可用。

  **实现思路**(给新会话):
  1. 在 `ChrootRuntime` 里加一个 `rootAvailable(context): Boolean` 探测函数
     (或复用现有 `suBinary()`,它在 `ChrootRuntime.kt` 里已遍历 Magisk/KSU 标准位置
     找 su,直接拿来用)。
  2. 探测逻辑:`suBinary()` 返回 null → 无 root → false。找到 su 后,异步跑一次
     `su -c id -u` 取 stdout,trim 后 `== "0"` 才算真 root;su 不弹授权框或拒绝授权
     时该命令会失败(非 0 退出 / 无输出)→ 一并判 false。**不要用 `Os.getuid() == 0`
     判定**——那是 app 自己的 uid,普通 app 永远不是 0,跟有没有 root 授权无关。
  3. 缓存:在 `MainActivity` 启动时(`onCreate` / 首次 `refresh`)后台跑一次探测,把
     结果存进一个 `rootAvailable` state(默认 false,避免冷启动闪一下可点)。探测是
     异步 I/O(起进程跑 su),务必在子线程做,`ui.post` 回主线程写 state。
  4. UI:`PerformanceDialog` 的 chroot `ToggleRow` 传 `enabled = rootAvailable &&
     !<其它已禁用条件>`;当前代码里它没传 `enabled`(默认 true),改成依赖
     `rootAvailable`。hint 在 `!rootAvailable` 时显示 `R.string.perf_chroot_no_root`
     (新字符串,如 “Needs root (a working su).”)。
  5. 边界:探测结果只在启动时取一次;若用户在 app 运行期间才去系统里授予 root
     (如装 Magisk 后),需重启 app 才生效——可接受,文档里注明。若想更主动,可在打开
     Performance 对话框时(`onPerformance` → `refreshCores()` 那条路径)再探一次。
  6. 文案复用:已有 `chroot_no_desktop` / `chroot_no_flatpak` 是 toast 用的;这里需要
     新增一个 settings hint 串,别复用那两条(语义不同)。

  涉及文件:`ChrootRuntime.kt`(加探测)、`MainActivity.kt`(state + 启动探测)、
  `PerformanceDialog.kt`(`enabled` 依赖 + hint 分支)、`strings.xml`(新 hint 串)、
  可能 `DeviceReport.kt`(报告一行 `root available` 方便 logcat 诊断)。

---

## 三、改动文件清单

| 文件 | 状态 | 说明 |
|---|---|---|
| `app/src/main/java/com/droiddeck/launcher/runtime/ChrootRuntime.kt` | 新建 | 后端核心 |
| `app/src/main/assets/chroot-entry.sh` | 新建 | 入口+清理脚本模板 |
| `app/src/main/assets/chroot-stop.sh` | 新建 | 强制停止脚本 |
| `app/src/main/java/com/droiddeck/launcher/session/SessionPrefs.kt` | 改 | 加 BACKEND_* + getter/setter |
| `app/src/main/java/com/droiddeck/launcher/session/SessionService.kt` | 改 | runSession 分叉 + stop 调用 + import + bwrap 环境门控 |
| `app/src/main/java/com/droiddeck/launcher/App.kt` | 改 | BwrapSpawner.start 门控 |
| `app/src/main/java/com/droiddeck/launcher/runtime/GuestCommand.kt` | 改 | bwrap 环境变量门控 |
| `app/src/main/java/com/droiddeck/launcher/MainActivity.kt` | 改 | onDesktop/onFlatpakApp 门控 |
| `app/src/main/java/com/droiddeck/launcher/agent/AgentStartActivity.kt` | 改 | desktop 模式门控 |
| `app/src/main/java/com/droiddeck/launcher/runtime/UserApps.kt` | 改 | addFlatpak/addFlatpakBundle 门控 |
| `app/src/main/res/values/strings.xml` | 改 | 加 chroot_no_desktop / chroot_no_flatpak |
| `su` | 未改 | 用户提供,仅参考其 su 路径列表(已并入 ChrootRuntime.SU_PATHS) |

---

## 四、设计要点回顾(给新会话上下文)

- **容器是会话级单实例**:整个 `proot ... droiddeck-session` 链换成 `su -c chroot-entry.sh`
  一个最外层入口;gamescope→steam→Proton→Wine→游戏 全是 chroot 后代,自动隔离,**游戏
  侧零适配**。
- **bind 计算复用**:`LinuxRuntime.binds()` 吐 `host:guest` 规格,proot 用 `-b`,chroot 用
  `mount --bind`,执行器换、bind 逻辑不动。
- **残存问题三层兜底**:trap 清理 + watchdog(app 死也清)+ `ChrootRuntime.stop` root 强制。
  这是针对用户实际痛点设计的,核心价值所在。
- **窄路径**砍掉 BwrapSpawner 整条路径,不动 Flatpak 沙箱语义(直接禁用)。
