# koishi-plugin-litematic-renderer

自动识别 QQ 群消息中小于指定大小的 `.litematic` 文件，并发送两张 PNG：

- 正二轴测
- 反向正二轴测（在基准角度上旋转 180°）

可选择合并转发，或将两张图片和投影信息组成一条普通群消息。消息中的信息包含保存者游戏 ID、创建时间、方块数/体积、尺寸、Litematic 版本和游戏数据版本。

不再生成正视、侧视或俯视图。默认 `standalone` 后端只启动独立 Java 进程，直接读取 Litematic、Minecraft 资源 JAR 和配置的材质包，不要求启动客户端或进入世界。材质包越靠后优先级越高。

## 独立 Java 渲染器

内置 JAR 支持标准资源包的 blockstate variants/multipart、模型继承、元素旋转、纹理覆盖与透明纹理，并能从游戏资源读取箱子、潜影盒和木牌的实体纹理与专用几何。尚未适配的特殊方块实体会明确显示缺失纹理，不会借用其他方块伪装；需要覆盖全部客户端实体渲染器时仍可切换到 `java` Fabric 桥接后端。

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
    maxFileSize: 1048576
    cacheDirectory: data/litematic-renderer-cache
    cacheMaxSizeGb: 20
    renderEngine: standalone
    standaloneJavaCommand: java
    minecraftJarPath: C:/path/to/.minecraft/versions/1.21.1/1.21.1.jar
    resourcePackPaths:
      - C:/path/to/.minecraft/resourcepacks/your-base-pack.zip
      - C:/path/to/.minecraft/resourcepacks/your-overlay-pack.zip
    standaloneRenderTimeout: 180000
    javaResolution: 1024
    javaSupersampling: 2
    isometricRotation: 135
    isometricSlant: 36
    isometricFill: 0.78
    background: '#000000'
    transparentBackground: false
    sendAsForward: false
    replyAndMention: true
    groupSendOptions:
      - groupId: '123456789'
        sendMode: forward
        replyAndMention: disabled
      - groupId: '987654321'
        sendMode: combined
        replyAndMention: inherit
```

请将示例中的 Java、Minecraft JAR 与材质包路径替换为本机实际路径；材质包越靠后优先级越高。

缓存按插件版本、投影文件 SHA-256 和渲染配置分目录，同时保存原始 `.litematic` 与两张 PNG。插件升级后使用新的版本目录，旧缓存不会因升级被主动删除；所有版本合计超过 `cacheMaxSizeGb` 时才按最久未使用顺序清理。

`isometricRotation` 控制第一张图的方向，第二张图始终自动增加 180°。`isometricSlant: 36` 对应 Isometric Renders 的正二轴测预设。`javaSupersampling: 2` 会以最终边长的两倍离屏渲染，再高质量缩小。

`sendAsForward` 是未配置群覆盖时的默认发送方式：`true` 为合并转发，`false` 为联合发送。联合发送会把两张图片和投影信息放在同一条普通消息中。`replyAndMention` 控制是否引用原消息并 @ 投影发送者；`groupSendOptions` 可按群号覆盖这两个设置，相同群号以最后一项为准。合并转发受 QQ 协议限制，不能直接携带引用，因此开启回复 @ 时会先发送一条引用提示，再发送转发内容。

可用命令 `litematic.render <文件 URL>` 手动渲染直链文件。管理员可执行 `litematic.cache.clear` 清理缓存。

## 降级后端

`webgl` 使用内置 Deepslate 方块模型与纹理，`cpu` 生成简化方块预览；两者也只输出正反两张正二轴测图，但不会包含 Minecraft 客户端资源包效果。Java 桥不可用时会明确报错，不会静默发送材质不一致的图片。

## 参考实现

Java 渲染桥基于以下 MIT 项目的公开渲染流程进行集成：

- Isometric Renders by glisco
- Fabrishot by Ramid Khan
