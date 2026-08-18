# MINIX 文档索引

MINIX 当前采用证书绑定、`android:sharedUserId`、同 UID 校验和普通非导出 Binder Service 的单一控制链。实现代码位于 `me.dartcv.minix.control`，服务组件为 `ControlService`，AIDL 接口为 `IControlBridge`。

## 当前基线

| 文档 | 用途 | 状态 |
|---|---|---|
| [`2026-08-18_架构-Control传输说明-report.md`](2026-08-18_架构-Control传输说明-report.md) | 控制传输边界、身份门禁和迁移记录 | 当前生效 |
| [`../codex.md`](../codex.md) | 功能矩阵、构建命令和验收门禁 | 当前生效 |
| [`build-signing.md`](build-signing.md) | 外部签名配置和发布检查 | 当前生效 |
| [`2026-08-17_逆向移植-MINIX功能接线-report.md`](2026-08-17_逆向移植-MINIX功能接线-report.md) | 功能接线与运行时状态 | 当前主报告 |
| [`2026-08-18_headspin-gated-implementation-report.md`](2026-08-18_headspin-gated-implementation-report.md) | head-spin 内部门禁与 native adapter | 当前专项报告 |
| [`2026-08-17_reverse-minix-antiflash-runtime-report.md`](2026-08-17_reverse-minix-antiflash-runtime-report.md) | 防闪运行时验收 | 当前专项报告 |
| [`2026-08-17_逆向-HITBOX静态恢复-report.md`](2026-08-17_逆向-HITBOX静态恢复-report.md) | HITBOX 静态恢复记录 | 当前专项报告 |

## 传输迁移

本次重构将 Java/Kotlin 包、AIDL descriptor、Service、客户端、Controller、JNI 导出名和测试统一收敛到 `control` 命名空间。协议版本为 v11，用于让旧 descriptor 客户端在连接阶段失配并重新编译。

方法顺序、JSON 字段、功能 wire ID、shared UID、签名身份和 `:control` Service 进程保持稳定；旧包路径和兼容别名已从源码树移除。

## 推荐阅读顺序

1. [Control 传输说明](2026-08-18_架构-Control传输说明-report.md)
2. [`../codex.md`](../codex.md)
3. [功能接线报告](2026-08-17_逆向移植-MINIX功能接线-report.md)
4. 按需阅读防闪、HITBOX 和 head-spin 专项报告。

## 源码索引

| 组件 | 路径 |
|---|---|
| Binder Service | `app/src/main/java/me/dartcv/minix/control/ControlService.kt` |
| Binder 客户端 | `app/src/main/java/me/dartcv/minix/control/ControlBridgeClient.kt` |
| 控制会话 | `app/src/main/java/me/dartcv/minix/control/ControlController.kt` |
| 目标身份与会话 | `app/src/main/java/me/dartcv/minix/control/ControlTargetSession.kt` |
| AIDL | `app/src/main/aidl/me/dartcv/minix/control/IControlBridge.aidl` |
| native adapter | `app/src/main/java/me/dartcv/minix/control/nativeadapter/TargetNativeProbe.kt` |
| C++ probe | `app/src/main/cpp/target_native_probe.cpp` |
| 静态校验工具 | `tools/` |
