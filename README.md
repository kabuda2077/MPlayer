# MPlayer

MPlayer 是一款 Android 音乐播放器，应用内名称为 **PixelPlayer**。

## 功能

- 本地音乐扫描与管理
- 歌曲、专辑、艺术家、歌单、收藏和文件夹浏览
- MP3、FLAC、AAC、M4A、OGG、Opus、WAV 等常见音频格式
- 网易云音乐、QQ 音乐、酷狗、酷我在线搜索
- 网易云音乐和 QQ 音乐在线推荐、每日推荐、新歌速递、推荐歌单和榜单
- 音源扩展功能
- 播放队列、随机播放、循环播放和交叉淡入淡出
- 后台播放、媒体通知、Android Auto 和桌面小组件
- 内嵌歌词、本地 LRC 和在线歌词
- 逐行、逐字、翻译和罗马音歌词显示
- 歌词编辑、歌词缓存和手动搜索歌词
- 在线歌曲下载与本地音乐库管理
- 标签、封面、艺术家和专辑信息编辑
- 播放历史、播放统计和 ReplayGain
- 均衡器、主题、自定义界面和动画效果
- 歌单、收藏、歌词、设置和音源扩展备份恢复
- AI 歌单和 AI 辅助功能

## 系统要求

- Android 11（API 30）或更高版本
- 推荐至少 6 GB RAM

## 开发环境

- Android Studio Ladybug（2024.2.1）或更新版本
- JDK 21
- Android SDK 37

## 构建

```bash
git clone https://github.com/kabuda2077/MPlayer.git
cd MPlayer
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

运行编译和单元测试：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest
```

## 项目结构

```text
app/src/main/java/com/theveloper/pixelplay/
├── data/             # 数据、网络、播放、歌词、下载和备份
├── di/               # 依赖注入
├── presentation/     # 页面、组件和 ViewModel
├── ui/               # 主题和桌面小组件
└── utils/            # 通用工具
```

## 贡献

欢迎提交 Issue 和 Pull Request。提交前请运行编译和单元测试，并避免提交本地配置、签名文件和构建产物。

## 许可证

本项目使用仓库中的[专有许可证](LICENSE)。未经版权所有者许可，不得重新分发或商业化发布。

