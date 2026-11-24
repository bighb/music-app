# 验证手册

本文件说明哪些结论已被自动化测试覆盖，哪些必须在真机上人工确认。
**"未验证"的项目在真机确认前不要当作已修复。**

## 1. 离线单测（每次改动手动/自动跑）

```
gradlew.bat :app:testDebugUnitTest --console=plain
```

全部走真实生产代码，不打网络。截至 v1.3.1：129 个用例，121 通过，8 个联网用例跳过。

| 文件 | 覆盖内容 |
|---|---|
| `TimeoutPolicyTest` | 每个源的 connect/read/write 不超过自身搜索截止；callTimeout == searchDeadlineMs；所有客户端共享连接池与 Dispatcher |
| `SourceCallTest` | 200 + 0 行 → `EMPTY`；超时 → `TIMEOUT`；解析异常 → `PARSE_ERROR`；HTTP 错误码透出；协程取消不被吞；超时兜底 → `HANG` |
| `SearchMergerTest` | 到达顺序、id 去重、同名同艺人合并优先国内、被丢弃行可回查、索引有上限、`reset()` 清空 |
| `SearchCacheTest` | 降级快照 20s 过期、完整结果 10 分钟、降级永不被读作完整 |
| `KuwoJsonTest` | 歌名含 `'`（`Don't Stop`、`爱'你`、`Rock 'n' Roll`）不破坏整批；HTML 实体解码；标题回退最多 2 组关键词 |
| `NeteaseParseTest` | 无 `picUrl` 时封面为空串而非崩溃；补封面失败不丢结果 |
| `StreamResolverTest` | 候选顺序、请求次数上限、推广片段继续换源、`/404` 与 `404.html` 拒绝、10s 总截止、只重试一次、缓存 TTL、被拒绝的直链不再返回 |
| `PlaceholderGateTest` | 11s vs 240s 判占位；238s vs 240s 通过；无时长信息时放行 |
| `QueueDiffTest` | 插入/移除/拖动各对应一次队列编辑；多行变化退化为重建；空队列进出是单次编辑 |
| `FailureKindTest` | 文案表、日志行格式、"已稳定"的定义 |
| `SearchStreamingTest` | 真实 `AudioSourceManager` 扇出：YouTube 慢不拖住判定、单源超时不中止其他源 |

## 2. 联网单测（默认 `@Ignore`，需要时手动打开）

以下用例会请求真实接口，已标注 `@Ignore("live network，见 docs/VERIFICATION.md")`：

- `AudioSourceTest`：酷我回退直链、聚合搜索、网易搜索与封面
- `YouTubeAudioSourceTest`：YouTube 搜索与直链解析
- `SearchSuggestionAndRankingTest.testSearchSuggestHelperSuppressesShareLinks`

临时运行：去掉对应方法的 `@Ignore`，跑完再加回去。

**开发机网络 ≠ 手机网络**（尤其 YouTube 可达性），这些用例通过不代表手机上同样可用。

## 3. 必须在真机上确认的事项（v1.3.1 尚未验证）

开发时没有连接 adb 设备，以下都没有实测。

### 3.1 车机切歌（本次改动的核心）

1. 播放队列第 1 首，按方向盘"下一首" → 应切到第 2 首；按到末尾应回绕到第 1 首。
2. 没有实体方向盘时用同一通道模拟：
   ```
   adb shell media dispatch skip_to_next
   adb shell media dispatch skip_to_prev
   adb shell media dispatch play
   adb shell media dispatch pause
   ```
3. 通知栏的上/下一首按钮应同时可用，车机屏幕上的队列应显示完整列表。
4. 一首播完应自动进入下一首；`单曲循环` / `随机` 在方向盘与界面上表现一致。
5. 播放出错（例如断网后点歌）后，再点其他歌曲应能正常播放——确认错误状态能干净退出：
   ```
   adb logcat -v time | grep -E "MusicPlayerManager|onPlayerError|PlaybackException"
   ```
6. 播放队列抽屉里拖动排序、移除当前曲目、插入下一首：当前曲目不应重启或跳曲。

### 3.2 音源与日志

1. 搜索含 `'` 的歌名（如 `Don't Stop`、`爱'你`）：酷我应有结果。
2. 搜索冷门歌并断网再恢复：提示应是"音源暂时连不上 [重试]"而不是"受版权保护"。
3. 调试图标 → 导出 Markdown，确认含 `[SRC]` `[SRCH]` `[RESL]` `[REJ ]` 行，且不含完整签名直链。
4. 调试图标红点：正常弱网超时不应点亮（只有 HTTP 错误、推广片段、降级汇总才点亮）。
5. 下载一首歌：落地文件应为音频，不是 HTML 错误页。
6. 下载文件的用户代理本次**未改动**（原计划的 UA 对比未做）。

### 3.3 新旧搜索预算对比（可选，量化超时丢了多少歌）

`search_budget_scale` 仅用于诊断，不在界面中暴露（100 = 正式预算，45 ≈ 旧版 4s/3.5s）。

```
adb shell run-as com.personal.musicapp sh -c "printf '<?xml version=\"1.0\"?><prefset><long name=\"search_budget_scale\" value=\"45\"/></prefset>' > shared_prefs/music_app_prefs.xml"
adb shell am force-stop com.personal.musicapp
# 同一批关键词各搜一遍并导出日志，再改回 100 重复一次
grep -oE 'status=[a-z-]+' after.txt | sort | uniq -c
```

调试日志最多保留 120 条（约 30 次搜索），两轮之间先清空日志。

## 4. 已知未做项

- 远程片段探测（`Range` 请求探测时长）未实现；按时长对账与本地文件校验已覆盖主要场景。
- 随机播放由"每次随机"变为 ExoPlayer 的稳定随机顺序（一轮内不重复）。
- 连续 3 首解析失败后停止自动跳过。
- 时长对账只拒绝"明显偏短"的流，不拒绝偏长的。
- 队列拖动非当前曲目使用"移除 + 插入"实现（当前曲目用 `moveMediaItem`），因为未能在真机确认该版本 `moveMediaItem` 前向移动的下标语义。
