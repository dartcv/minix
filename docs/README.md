# MINIX 文档索引

> **阅读入口：** 先看 [Root 方案弃用说明](2026-08-18_架构-Root方案弃用说明-report.md)，再看项目执行基线 [`../codex.md`](../codex.md)。
>
> 当前架构是证书 + `android:sharedUserId` + 同 Linux UID + 普通非导出 `:control` Service/Binder。文档或源码路径中的 `root` / `Root*` 仅是历史兼容名称，不代表 Root、`su` 或 UID 0 能力。

## 当前基线与架构

构建签名与密钥外置方式见 [`构建签名说明`](build-signing.md)。仓库不包含 keystore、设备 APK 或运行时抓取物。

| 文档 | 用途 | 状态 |
|---|---|---|
| [`2026-08-18_架构-Root方案弃用说明-report.md`](2026-08-18_架构-Root方案弃用说明-report.md) | Root 方案弃用、证书/shared UID/Binder 现状、历史命名迁移规则 | 当前生效 |
| [`../codex.md`](../codex.md) | 唯一迁移执行基线、构建/验收命令、功能矩阵和证据门禁 | 当前生效 |
| [`2026-08-17_逆向移植-MINIX功能接线-report.md`](2026-08-17_逆向移植-MINIX功能接线-report.md) | FAKE_FLIGHT、SearchID、LIFE_STATE、HITBOX 与 SetheadSpeed 接线结果 | 当前主报告 |
| [`2026-08-18_headspin-gated-implementation-report.md`](2026-08-18_headspin-gated-implementation-report.md) | head_spin 静态恢复、内部 supervisor/native adapter、fixture 与剩余 runtime 门禁 | 当前专项报告 |
| [`2026-08-17_reverse-minix-antiflash-runtime-report.md`](2026-08-17_reverse-minix-antiflash-runtime-report.md) | 防闪运行时验收与设备证据 | 当前专项报告 |
| [`2026-08-17_逆向-HITBOX静态恢复-report.md`](2026-08-17_逆向-HITBOX静态恢复-report.md) | HITBOX 本地链、旧 worker 与证据门禁 | 当前专项报告 |
| [`../work/feature-readiness-audit-20260818.json`](../work/feature-readiness-audit-20260818.json) | AIM、DRAW、head_spin 的统一静态就绪度与下一步闭合工件 | 当前静态审计 |

## 历史专项报告

以下报告保留原始时间线和文件路径；其中出现的 `Root*` 术语按弃用说明解释：

| 文档 | 原始主题 | 阅读提示 |
|---|---|---|
| [`2026-08-12_reverse-minix-root-injection-report.md`](2026-08-12_reverse-minix-root-injection-report.md) | shared-UID 控制服务与 GameApp 静态还原 | 标题中的 root 是历史命名；正文已描述普通 Service 路线 |
| [`2026-08-12_reverse-minix-shared-uid-control-report.md`](2026-08-12_reverse-minix-shared-uid-control-report.md) | shared-UID 控制服务与 GameApp 静态还原 | 与同日 root-injection 报告内容重叠，作为时间线留档 |
| [`2026-08-13_reverse-minix-antiflash-report.md`](2026-08-13_reverse-minix-antiflash-report.md) | 防闪静态 profile、native cycle 与回滚 | 以证书/shared UID/Binder 边界为准 |

## 推荐阅读顺序

1. [Root 方案弃用说明](2026-08-18_架构-Root方案弃用说明-report.md)：先建立命名和身份模型。
2. [`../codex.md`](../codex.md)：按“身份 → 查找 → 探测 → 解析 → 读写 → 验证”执行。
3. [功能接线主报告](2026-08-17_逆向移植-MINIX功能接线-report.md)：查看当前功能状态和残余门禁。
4. 按需阅读防闪、HITBOX 和历史专项报告；设备测试以报告中的 Evidence/Path 和当前构建工件为准。

## 术语速查

| 旧称 | 当前文档称呼 | 说明 |
|---|---|---|
| `RootFeatureService` | 控制服务 | 普通 `android.app.Service`，`exported=false`，运行于 `:control` |
| `RootFeatureBridgeClient` / `IRootFeatureBridge` | 控制桥 / AIDL 桥 | 普通 Binder 调用，协议版本以当前基线为准 |
| `RootController` | 控制会话协调器 | 管理目标身份、功能状态、重试和回滚 |
| `root` 包目录 | 历史兼容命名空间 | 不改变证书、shared UID 或权限模型 |

## 源码迁移状态

- UI、ViewModel 和 Action 回调已使用 `Control` 语义（连接服务、目标会话、功能开关、轮询）。
- `MinixApplication.rootController` 仅保留为 `@Deprecated` 源兼容别名；新代码使用 `controlController`。
- `TransportPolicy.current` 固定声明 `CERTIFICATE_SHARED_UID_BINDER`，并将 Root/su 入口标记为关闭；该对象不执行运行时操作。
- `root` 包、AIDL descriptor、Manifest Service 名称和 JNI 符号暂不改动，避免破坏现有安装身份与 Binder 兼容性。
