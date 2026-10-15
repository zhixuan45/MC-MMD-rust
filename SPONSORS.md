# 赞助者名单

“关于 → 赞助者”读取发布包内的 `common/src/main/resources/assets/mmdskin/about/sponsors.json`。
当前没有名单，数据保持空数组 `[]`，界面显示“感谢所有支持本项目的赞助者”。

新增名单时编辑两个版本分支中的该数据文件，无需修改 Java 界面代码。每条记录支持：

```json
[
  {
    "name": "显示名称",
    "description": "可选的感谢说明",
    "url": "https://example.com/"
  }
]
```

`name` 必填；`description` 和 `url` 可省略。链接支持 HTTP/HTTPS，可在界面点击打开。
界面自动换行、滚动显示名单；无效记录跳过，名单不可用时显示默认感谢信息。
