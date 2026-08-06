# koishi-plugin-litematic-renderer

[![npm](https://img.shields.io/npm/v/koishi-plugin-litematic-renderer?label=npm)](https://www.npmjs.com/package/koishi-plugin-litematic-renderer)

自动识别 QQ 群消息中小于指定大小的 `.litematic` 文件，并发送两张 PNG：

- 正二轴测
- 反向正二轴测（在基准角度上旋转 180°）

可选择合并转发，或将两张图片和投影信息组成一条普通群消息。消息中的信息包含保存者游戏 ID、创建时间、方块数/体积、尺寸、Litematic 版本和游戏数据版本。

不再生成正视、侧视或俯视图。默认 `standalone` 后端只启动独立 Java 进程，直接读取 Litematic、插件内置的 Minecraft 26.2 原版资源和配置的材质包，不要求启动客户端、进入世界或另行下载客户端 JAR。材质包越靠后优先级越高。

## 独立 Java 渲染器

内置 JAR 支持标准资源包的 blockstate variants/multipart、模型继承、元素旋转、纹理覆盖与透明纹理，并能从游戏资源读取箱子、潜影盒、木牌、头颅和旗帜的实体纹理与专用几何。玩家头颅会读取 Litematic 中的皮肤属性，并且只允许从 Mojang 的 `textures.minecraft.net` 获取皮肤；旗帜支持新旧 NBT 的底色与图案叠加。尚未适配的特殊方块实体会明确显示缺失纹理，不会借用其他方块伪装；需要覆盖全部客户端实体渲染器时仍可切换到 `java` Fabric 桥接后端。

## Java 渲染桥

将 `litematic-render-bridge-0.1.2.jar` 放入 `1.21.1-Fabric/mods`。机器人运行期间需要保持该 Minecraft 客户端打开并进入任意世界。桥接模组会在游戏目录的 `render-bridge` 下建立任务队列，并在隔离的原理图世界中渲染，不会添加或修改玩家世界中的方块。

当前配置要求同时启用以下资源包，且附加包应位于基础包上方：

1. `XeKr红显3.6forMC1.20.2~1.21.5.zip`
2. `XKRDA红显附加包0.3for1.19.4~1.21snapshot.zip`

Java 桥使用 Isometric Renders 的正交投影、旋转和光照流程，并以 Fabrishot 的高分辨率帧缓冲思路进行超采样后再缩小保存。

## 配置示例

```yaml
plugins:
  litematic-renderer:
    maxFileSize: 1024
    cacheDirectory: data/litematic-renderer-cache
    cacheMaxSizeGb: 20
    renderEngine: standalone
    # 推荐 Java 21+；留空自动查找，也可填写自定义 Java 可执行文件路径
    standaloneJavaCommand: ''
    # 单次 Java 渲染最大堆内存，以及内存不足后的全新进程重试次数
    standaloneJavaMaxHeapMb: 200
    standaloneJavaRetryMaxHeapMb: 2048
    standaloneJavaMemoryRestartLimit: 1
    # 留空或文件不存在时使用插件内置的 26.2 原版资源
    minecraftJarPath: ''
    # 自定义材质包覆盖内置原版资源；越靠后优先级越高
    resourcePackPaths: []
    standaloneRenderTimeout: 180000
    javaResolution: 1024
    javaSupersampling: 2
    isometricRotation: 135
    isometricSlant: 36
    isometricFill: 0.78
    background: '#000000'
    transparentBackground: false
    sendAsForward: false
    showViewTitles: false
    replyAndMention: true
    groupSendOptions:
      - groupId: '123456789'
        sendMode: forward
        replyAndMention: disabled
      - groupId: '987654321'
        sendMode: combined
        replyAndMention: inherit
```

`standaloneJavaCommand` 留空时会自动查找 Java 21 或更高版本；推荐使用 Java 21+，也可以填写自定义 Java 可执行文件路径。首次独立渲染默认将 Java 堆限制为 200 MiB；如果发生 OOM，插件会退出该进程并按 `standaloneJavaRetryMaxHeapMb`（默认 2048 MiB）启动全新进程重试。正反视图按顺序渲染，降低峰值内存；每次任务结束 Java 进程都会退出。无法正常解析的方块会写入 `diagnosticsFilePath`，可使用 `litematic.errors.export` 或 `/litematic-renderer/diagnostics` 导出。
插件卸载、重载或 Koishi 关闭时，会终止所有由本插件启动且仍在运行的独立渲染 Java 进程树；渲染超时也使用同一回收机制，不会影响 Minecraft 客户端或其他 Java 程序。
普通 `minecraft:chain` 已补充 26.2 模型兼容处理：按 `axis=x/y/z` 旋转链模型，不再因为原版资源包缺少普通链 blockstate 而退回完整立方体。

`minecraftJarPath` 是兼容旧配置的可选基础资源路径；留空、文件不存在或无需固定其他游戏版本时，独立渲染器直接使用插件内置的 26.2 原版资源包。需要自定义材质包时再设置 `resourcePackPaths`，越靠后优先级越高。使用 `java` Fabric 桥接后端时，请填写 `javaBridgeDirectory`。

缓存按插件版本、投影文件 SHA-256 和渲染配置分目录，同时保存原始 `.litematic` 与两张 PNG。插件升级后使用新的版本目录，旧缓存不会因升级被主动删除；所有版本合计超过 `cacheMaxSizeGb` 时才按最久未使用顺序清理。

`isometricRotation` 控制第一张图的方向，第二张图始终自动增加 180°。`isometricSlant: 36` 对应 Isometric Renders 的正二轴测预设。`javaSupersampling: 2` 会以最终边长的两倍离屏渲染，再高质量缩小。

`sendAsForward` 是未配置群覆盖时的默认发送方式：`true` 为合并转发，`false` 为联合发送。联合发送会把两张图片和投影信息放在同一条普通消息中。`replyAndMention` 控制是否引用原消息并 @ 投影发送者；`groupSendOptions` 可按群号覆盖这两个设置，相同群号以最后一项为准。合并转发受 QQ 协议限制，不能直接携带引用，因此开启回复 @ 时会先发送一条引用提示，再发送转发内容。

可用命令 `litematic.render <文件 URL>` 手动渲染直链文件。管理员可执行 `litematic.cache.clear` 清理缓存。

`showViewTitles` 默认关闭，发送图片时不再附带“正二轴测”和“反向正二轴测（旋转 180°）”标题；打开后恢复这两行文字。`diagnosticsFilePath` 同时保留每次渲染目录中的 `render-diagnostics.json` 和一个全局汇总文件，方块问题写入 `blocks`，渲染失败写入 `errors`。缓存目录名会保留投影文件名（包括中文）。
附件大小超过 `maxFileSize` 时会直接回复“文件大小超过 X MB，无法渲染”，不会静默跳过。
插件版本更新时，旧的全局诊断会自动按旧版本和日期移动到 `data/litematic-renderer-diagnostics-archive`；管理员执行 `litematic.errors.disable` 后，当前快照会移动到 `data/litematic-renderer-diagnostics-disabled`，活动文件会重新开始统计。
独立渲染器支持 `item_frame` 和 `glow_item_frame` 实体，会绘制展示框及其 `Item.id` 指定的物品纹理。
`maxFileSize` 的配置单位为 KB，默认 `1024` 即 1 MB；超过限制会回复文件大小和“无法渲染”提示。
渲染失败消息不会显示本机文件路径；详细路径仅保留在 Koishi 日志和诊断文件中。
自动处理群文件时，渲染失败或文件超限会引用原消息并 @ 发文件的用户。

## 降级后端

`webgl` 使用内置 Deepslate 方块模型与纹理，`cpu` 生成简化方块预览；两者也只输出正反两张正二轴测图，但不会包含 Minecraft 客户端资源包效果。Java 桥不可用时会明确报错，不会静默发送材质不一致的图片。

## 参考实现

Java 渲染桥基于以下 MIT 项目的公开渲染流程进行集成：

- Isometric Renders by glisco
- Fabrishot by Ramid Khan
