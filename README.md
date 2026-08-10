# MPlayer

MPlayer 是一款面向 Android 的本地与在线音乐播放器，应用内名称为 **PixelPlayer**。项目使用 Jetpack Compose 构建，重点提供稳定的本地音乐管理、在线搜索、QQ/网易云内容浏览，以及可扩展的音源插件播放能力。

> 当前项目仍在持续开发中。在线服务依赖平台接口和音源插件，具体可用内容可能随平台接口、登录状态和插件状态变化。

## 功能概览

### 音乐库

- 扫描并管理设备上的本地音乐。
- 支持歌曲、专辑、艺术家、歌单、收藏和文件夹视图。
- 支持 MP3、FLAC、AAC、M4A、OGG、Opus、WAV 等常见格式。
- 支持编辑歌曲标签、封面、艺术家和专辑信息。
- 支持播放历史、统计、排序、分组、随机播放和队列管理。
- 下载的在线歌曲通过 Android MediaStore 保存，并可作为本地歌曲管理。

### 在线搜索与发现

- 搜索网易云音乐、QQ 音乐、酷狗和酷我歌曲。
- 在线搜索结果不会因为浏览或播放自动写入音乐库。
- 网易云和 QQ 音乐支持在线页中的每日推荐、每日 30 首、新歌速递、推荐歌单和榜单入口。
- 推荐内容按平台卡片展示，歌单和榜单打开后按需加载完整歌曲列表。
- 在线歌曲可播放、加入队列或下载；网易云和 QQ 歌曲也可主动保存到资料库。

在线播放需要先安装并启用音源插件。插件负责根据平台歌曲信息解析可播放音频地址，应用会对解析结果进行校验、缓存和失效恢复。

### 歌词

- 优先使用歌曲内嵌歌词和同目录 LRC。
- 在线歌词支持 AMLL TTML、网易云官方歌词和 QQ QRC/LRC 等来源。
- 远程歌词候选按歌曲平台和匹配规则选择一个完整来源，不跨平台拼接歌词。
- 支持逐行、逐字、翻译和罗马音歌词（取决于实际歌词数据）。
- 支持歌词缓存、手动搜索、刷新和歌词编辑。

### 播放体验

- Media3 ExoPlayer 音频引擎，支持后台播放和系统媒体控制。
- 播放队列按需分批加载，适合较大的歌单。
- 支持播放/暂停、上一首/下一首、定位、循环、随机和交叉淡入淡出。
- 支持音质选择、在线音频缓存、ReplayGain 和媒体通知。
- 支持 Android Auto、主屏幕小组件和外部播放器入口。

### 备份与个性化

- 支持备份和恢复歌单、收藏、播放历史、歌词、均衡器、应用设置和音源插件。
- 登录信息及 AI API 配置属于单独的敏感数据模块，默认不导出。
- 备份文件当前未加密；如果选择敏感数据备份，请妥善保管 `.pxpl` 文件。
- 支持 AI 歌单和多种 AI provider 配置，相关功能需要用户自行提供 API 配置。

## 在线账号与隐私

- 网易云音乐和 QQ 音乐登录用于推荐、歌单同步和平台相关内容访问。
- Cookie 保存在应用的本地加密偏好存储中，不会上传到本项目的第三方服务器。
- 应用通过现有 Kotlin 网络层直接请求平台接口，不依赖 Python/Node 服务或公共代理部署。
- 在线内容、登录状态和接口能力受平台规则、Cookie 有效期及音源插件影响。
- 本项目不提供任何音乐版权内容；请仅使用你有权访问和保存的内容。

## 系统要求

- Android 11（API 30）或更高版本。
- 推荐至少 6 GB RAM，以获得更顺畅的歌词动画和大队列体验。

## 开发环境

- Android Studio Ladybug（2024.2.1）或更新版本。
- JDK 21。
- Android SDK 37。

## 构建

克隆仓库：

```bash
git clone https://github.com/kabuda2077/MPlayer.git
cd MPlayer
```

编译 Debug 版本：

```bash
./gradlew :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

运行检查：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

Debug 包使用独立的 applicationId：`com.theveloper.pixelplay.debug`。

### Release 签名

正式版需要在项目根目录提供本地签名文件：

- `vz-pixelplay.jks`
- `keystore.properties`

这两个文件已被 `.gitignore` 排除，不能提交到仓库。没有本地正式签名文件时，Gradle 会回退到 Debug 签名，仅适合本地测试。

## 项目结构

```text
app/src/main/java/com/theveloper/pixelplay/
├── data/
│   ├── backup/          # 备份与恢复
│   ├── database/        # Room 数据库、实体、DAO 和迁移
│   ├── download/        # 下载任务和 MediaStore 保存
│   ├── lyrics/          # 歌词获取、解析和缓存
│   ├── network/         # 网易云、QQ 音乐及其他网络请求
│   ├── plugin/          # 音源插件运行时与音频地址解析
│   ├── recommendation/  # 在线推荐、歌单和榜单
│   ├── repository/      # 音乐库、歌词和在线内容仓库
│   ├── service/         # MusicService、播放器和本地代理
│   └── worker/          # 后台同步任务
├── di/                  # Hilt 依赖注入
├── presentation/        # Compose 页面、组件和 ViewModel
├── ui/                  # 主题与主屏幕小组件
└── utils/               # 播放、媒体来源和通用工具
```

## 贡献

欢迎提交 Issue 和 Pull Request。提交改动前请至少运行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

请不要提交签名文件、Cookie、API key、`local.properties`、构建产物或本地 IDE 配置。

## 许可

本项目使用仓库中的[专有许可证](LICENSE)。未经版权所有者许可，不得重新分发、商业化发布或将衍生版本发布到应用商店。
