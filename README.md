# 媒体中心

把手机、平板、电视和 U 盘里的照片、视频、本地网页、文本，收进一个干净的本地浏览器。

不登录，不上云，不追踪。插上 U 盘就能看；电视遥控器也能用。

<p align="center">
  <a href="https://github.com/cheng2016/MediaCenter/releases/latest/download/MediaCenter-1.0.0.apk">
    <img src="https://img.shields.io/badge/下载_APK-1.0.0-1A73E8?style=for-the-badge" alt="下载 APK" />
  </a>
  &nbsp;
  <a href="https://github.com/cheng2016/MediaCenter/releases/latest">
    <img src="https://img.shields.io/github/v/release/cheng2016/MediaCenter?style=for-the-badge&label=Releases" alt="Releases" />
  </a>
</p>

<p align="center">
  <a href="https://github.com/cheng2016/MediaCenter/releases/latest/download/MediaCenter-1.0.0.apk"><b>立即下载 APK</b></a>
  · Android 7.0+
  · 手机 / 平板 / 电视
</p>

---

## 为什么用它

系统相册只爱图片和视频。U 盘里的 `index.html`、说明书、日志、离线教程，常常要换好几个 App，还可能被系统浏览器乱打开。

媒体中心按文件夹把东西摊开，点什么就用对应的查看器打开：

| 类型 | 打开方式 |
| --- | --- |
| 图片 | 全屏缩放，左右滑切换 |
| 视频 | 内置播放器，进度可恢复 |
| 网页 `.html` / `.htm` | 内置浏览器，同目录 CSS / 图片 / JS 一起加载 |
| 文本 `.txt` / `.md` / `.json` … | 等宽阅读，大文件自动截断 |
| 其它文件 | 交给系统里能打开它的应用 |

插上 U 盘会出现在左侧。电视上用方向键选菜单、确认打开、返回退出，不用碰屏幕。

## 适合谁

- 把资料、离线网页、教程拷进 U 盘，插到电视或平板上直接看
- 不想为了看几个本地 HTML 再装一套浏览器
- 需要一个不联网也能用的私人媒体柜

## 怎么用

1. [下载 APK](https://github.com/cheng2016/MediaCenter/releases/latest/download/MediaCenter-1.0.0.apk) 并安装（需允许未知来源）
2. 打开后授予 **全部文件访问**，才能看到网页、文本和 U 盘里的全部文件
3. 左侧选「内部存储」或 U 盘，进入文件夹浏览
4. 点网页文件，会用应用内置页面打开，返回键回到刚才的列表

电视：上/下切换左侧菜单，确认进入，右键跳到文件列表，左键回到菜单。

## 下载

安装包放在 [GitHub Releases](https://github.com/cheng2016/MediaCenter/releases/latest)，不进源码仓库。

- 最新包：[MediaCenter-1.0.0.apk](https://github.com/cheng2016/MediaCenter/releases/latest/download/MediaCenter-1.0.0.apk)
- 历史版本：https://github.com/cheng2016/MediaCenter/releases

## 自己编译

```bash
git clone https://github.com/cheng2016/MediaCenter.git
cd MediaCenter
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。

## 许可

源码以仓库为准。欢迎 Issue / PR。
