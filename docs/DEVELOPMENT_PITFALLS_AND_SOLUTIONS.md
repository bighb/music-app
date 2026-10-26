# Android 原生音乐播放器开发实战与踩坑排错笔记

> 本文档记录在构建本款原生 Android 音乐播放器（零服务器依赖、抖音音源解析、全网歌曲搜索与离线缓存）过程中遇到的核心技术难点、原因深度剖析及彻底解决方案。

---

## 目录
1. [踩坑一：抖音短视频分享旧接口下线与风控反爬机制](#1-踩坑一抖音短视频分享旧接口下线与风控反爬机制)
2. [踩坑二：ExoPlayer 跨协议重定向（HTTPS -> HTTP）静默终止](#2-踩坑二exoplayer-跨协议重定向https---http静默终止)
3. [踩坑三：音频容器解析失败 ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)](#3-踩坑三音频容器解析失败-error_code_parsing_container_unsupported-3003)
4. [踩坑四：Jetpack Compose 搜歌列表封面图丢失](#4-踩坑四jetpack-compose-搜歌列表封面图丢失)
5. [踩坑五：Local-First 电脑本地自动化测试与调试经验](#5-踩坑五local-first-电脑本地自动化测试与调试经验)

---

### 1. 踩坑一：抖音短视频分享旧接口下线与风控反爬机制

#### 🔴 遇到问题
- 手机端点击“解析链接”或直接在搜索框粘贴抖音分享口令（如 `v.douyin.com/...`）时，界面提示“*未能解析到有效音频*”。
- 尝试通过关键字搜索抖音音乐时无任何结果返回。

#### 🔍 根因剖析
1. **旧接口彻底失效**：早期的公开接口 `iesdouyin.com/web/api/v2/music/search` 与 `iteminfo` 已被字节跳动全量下线，请求均返回 `Url doesn't match` 或空 JSON。
2. **移动端页面风控反爬**：抖音移动端页面现在要求必须携带合法的 `ttwid` 会话凭证才能渲染出包含视频元数据的 `_ROUTER_DATA` 结构。
3. **Gson 解析空指针异常**：在提取 `_ROUTER_DATA.loaderData` 时，首个字段为 `"video_layout": null`。在 Kotlin 中如果不做类型检查直接调用 `.asJsonObject`，会立即触发 `IllegalStateException: Not a JSON Object: null`，导致解析异常退出。

#### 🟢 彻底解决方案
- **动态凭证注册**：在请求抖音分享页面前，异步向 `ttwid.bytedance.com/ttwid/union/register/` 动态注册获取有效的 `ttwid` Cookie。
- **防御性 JSON 遍历**：
  ```kotlin
  for (entry in loaderData.entrySet()) {
      if (!entry.value.isJsonObject) continue // 规避 null 引起的 IllegalStateException
      val pageObj = entry.value.asJsonObject
      val videoInfoRes = pageObj.getAsJsonObject("videoInfoRes") ?: continue
      val itemList = videoInfoRes.getAsJsonArray("item_list")
      if (itemList != null && itemList.size() > 0 && itemList[0].isJsonObject) {
          targetItem = itemList[0].asJsonObject
          break
      }
  }
  ```
- **文案智能提取 + 全网音源匹配双保险**：从分享口令中自动提取视频标题文案（如 `【DJ小明的作品】`），若原视频被抖音风控封锁，自动级联全网音乐库检索出完整高品质音频。

---

### 2. 踩坑二：ExoPlayer 跨协议重定向（HTTPS -> HTTP）静默终止

#### 🔴 遇到问题
- 歌曲能正常搜索出来，但点击歌曲条目后**没有任何声音，界面也不报错**，播放器一直处于暂停或 IDLE 状态。

#### 🔍 根因剖析
1. **协议重定向拦截**：歌曲的初始播放链接为 `https://music.163.com/song/media/outer/url?id=...`，服务器处理后会返回 `302 Found` 重定向至 CDN 音频节点 `http://m701.music.126.net/...`。
2. **ExoPlayer 安全默认策略**：现代 Android ExoPlayer 的 `DefaultHttpDataSource` **默认禁止 Cross-Protocol Redirect（从 HTTPS 重定向到 HTTP）**。遇到此类重定向时底层会直接抛出安全异常并中止。
3. **缺少错误捕获**：初期播放器未实现 `Player.Listener.onPlayerError`，导致异常被底层吞没，界面未能提供有效反馈。

#### 🟢 彻底解决方案
1. **配置数据源允许跨协议跳转**：
   ```kotlin
   val httpDataSourceFactory = DefaultHttpDataSource.Factory()
       .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
       .setAllowCrossProtocolRedirects(true)
   ```
2. **网络层提前预解析重定向**：在将音频链接塞给 ExoPlayer 前，由后台协程通过 OkHttp 异步读取 302 的 `Location` 响应头，并将地址直接转换为 `https://` 直链，完全绕过播放内核的跳转逻辑。
3. **实现错误回调与 UI 弹窗**：重写 `onPlayerError` 并在 `MainScreen` 中监听展示 Toast，杜绝静默失败。

---

### 3. 踩坑三：音频容器解析失败 ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003)

#### 🔴 遇到问题
- 部分公开歌曲、DJ 翻唱可以正常播放，但点击部分热门歌曲时弹窗报错：
  `播放出错: ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) - Source error`。

#### 🔍 根因剖析
- 报错 3003 代表 ExoPlayer 无法识别下载文件的媒体容器（Container）。
- 经抓包实测，当某首歌曲在网易云需要 VIP 或有版权限制时，`outer/url` 接口不会返回 403，而是直接 **302 重定向到了 `http://music.163.com/404`（一个 HTML 网页）**！
- 播放器接收到该地址后下载了网页 HTML 代码，并尝试用 MP3/AAC 提取器解析，发现开头是 `<!DOCTYPE html>`，从而抛出“不支持的媒体容器（3003）”。

#### 🟢 彻底解决方案
1. **404 嗅探与安全拦截**：在预解析 302 链接时，判断目标地址若包含 `/404` 或 `404.html`，立即终止并绝不传给播放器。
2. **多音源自动级联切换 ([`KuwoAudioHelper.kt`](file:///C:/code/music-app/app/src/main/java/com/personal/musicapp/data/source/KuwoAudioHelper.kt))**：
   - 当检测到首选音源受限时，程序在后台**毫秒级自动级联至酷我音乐无损库**检索同名歌曲。
   - 通过 `anti.s` 接口直接换取纯正的 `https://kw-lv.kuwo.cn/.../xxx.mp3`（实测返回 HTTP 200，MIME 类型为 `audio/mpeg`）。
   - 用户无感知即可顺利听到原本受限的歌曲。
3. **兜底状态反馈**：若极少数独家歌曲在所有免费库均无音源，主动弹出友好提示“*该歌曲受版权或VIP限制，暂无免费可用音源*”。

---

### 4. 踩坑四：Jetpack Compose 搜歌列表封面图丢失

#### 🔴 遇到问题
- 搜索歌曲后，列表能显示歌曲名、歌手和时长，但左侧封面图显示为默认音符占位图。

#### 🔍 根因剖析
- 基础搜索接口只返回歌曲和歌手的基础数据，其中歌手图片大多是默认灰度头像（`5639395138885805.jpg`），而真实的专辑封面 `album.picUrl` 在搜索结果中为 `null`。

#### 🟢 彻底解决方案
- 在 [`NetMusicAudioSource.kt`](file:///C:/code/music-app/app/src/main/java/com/personal/musicapp/data/source/NetMusicAudioSource.kt) 中引入**批量歌曲详情检索**：
  ```kotlin
  // 一次性通过单个网络请求批量获取全部专辑真实封面
  val detailUrl = "https://music.163.com/api/song/detail/?ids=[${songIds.joinToString(",")}]"
  ```
- 统一将图片协议升级为 `https://` 并追加 `?param=400y400` 高清裁切参数，使 Compose 的 Coil 图片加载引擎能够秒级渲染出清晰的专辑大图。

---

### 5. 踩坑五：Local-First 电脑本地自动化测试与调试经验

#### 🔴 遇到问题
- 每次修改代码后都要打包出 APK 传到真机安装测试，调试效率低下且无法直观查看网络请求的完整响应报文。

#### 🟢 彻底解决方案
1. **工程开启单元测试 Mock**：
   在 `app/build.gradle.kts` 中配置：
   ```kotlin
   testOptions {
       unitTests.isReturnDefaultValues = true
   }
   ```
2. **编写真实网络流单元测试 ([`AudioSourceTest.kt`](file:///C:/code/music-app/app/src/test/java/com/personal/musicapp/AudioSourceTest.kt))**：
   - 直接在电脑端执行 `.\gradlew.bat testDebugUnitTest`。
   - 验证音频搜索、封面 HTTP 200 状态码、音频 302 重定向解析、多音源级联全流程。
   - 跑通所有测试用例后再打包 APK，极大提升了开发和迭代的可靠性。
