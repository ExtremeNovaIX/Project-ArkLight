# GPT-SoVITS 语音链路

这条链路用于把 RP 的流式文本转成语音。Java 后端不打包 GPT-SoVITS，也不管理模型权重；它只负责：

- 接收 RP 输出的文本片段。
- 去掉 `[开心]` 这类前端表情标签。
- 按短句切分后调用 GPT-SoVITS 的 HTTP `/tts` 接口。
- 通过 `/api/tts/live` 把音频推给前端播放。

## 适合什么时候用

GPT-SoVITS 更适合已经训练好角色音色、希望稳定复用固定参考音频的场景。它的效果主要取决于 GPT-SoVITS 自己的模型配置和参考音频质量。

## 运行方式

推荐把整合包或源码放在：

```text
E:\PersonalProject\Project-1\backend\tts\runtime\GPT-SoVITS-v2pro
```

当前配置默认由 Java 拉起 API 服务。切到 `provider: gpt-sovits-http` 后，Java 会读取顶层 `tts.runtime`，
等价于在 GPT-SoVITS 工作目录执行：

```bat
runtime\python.exe api_v2.py -a 127.0.0.1 -p 9880 -c GPT_SoVITS/configs/tts_infer.yaml
```

如果你选择手动启动 GPT-SoVITS，也可以关闭 Java 自动启动：

```yaml
tts:
  runtime:
    auto-start-enabled: false
```

无论谁启动，最终都必须能访问：

```text
http://127.0.0.1:9880/tts
```

## Java 配置

配置文件：

```text
E:\PersonalProject\Project-1\config\application-tts.yaml
```

切到 GPT-SoVITS：

```yaml
tts:
  enabled: true
  provider: gpt-sovits-http
  gpt-so-vits:
    base-url: http://127.0.0.1:9880
    ref-audio-path: logs/rossi/5-wav32k/rossi.wav_0000000000_0000167360.wav
    aux-ref-audio-paths:
      - logs/rossi/5-wav32k/rossi.wav_0010851520_0011051840.wav
    prompt-text: 我是洛希娜，授名是狼魄，寓意是狼群的瑰宝。
    text-lang: zh
    prompt-lang: zh
    media-type: wav
    text-split-method: cut0
```

说明：

- `ref-audio-path` 是传给 GPT-SoVITS 服务端的路径，可以是绝对路径，也可以是相对 GPT-SoVITS 工作目录的路径。
- `prompt-text` 必须对应主参考音频里实际说出的文本。
- Java 已经做了短句切分，`text-split-method: cut0` 通常更适合低延迟播放。
- `aux-ref-audio-paths` 只传音频路径，不传辅参考文本。

## 直接测试

先确认服务活着：

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:9880/docs
```

再发一条 `/tts` 请求：

```powershell
$body = @{
  text = "你好，我是洛希娜，现在开始测试 GPT SoVITS 语音。"
  text_lang = "zh"
  ref_audio_path = "logs/rossi/5-wav32k/rossi.wav_0000000000_0000167360.wav"
  prompt_text = "我是洛希娜，授名是狼魄，寓意是狼群的瑰宝。"
  prompt_lang = "zh"
  media_type = "wav"
  text_split_method = "cut0"
  batch_size = 1
  speed_factor = 1.0
  streaming_mode = 0
} | ConvertTo-Json

Invoke-WebRequest `
  -Uri http://127.0.0.1:9880/tts `
  -Method POST `
  -ContentType "application/json" `
  -Body $body `
  -OutFile E:\PersonalProject\Project-1\backend\tts\runtime\gpt-sovits-test.wav
```

能生成可播放的 `gpt-sovits-test.wav`，说明 GPT-SoVITS 自身可用。

## 前端播放条件

前端能听到声音需要同时满足：

1. `tts.enabled=true`。
2. `provider: gpt-sovits-http`。
3. GPT-SoVITS `/tts` 服务可访问。
4. 当前网页已经订阅 `/api/tts/live`。
5. 浏览器允许页面播放音频。多数浏览器要求用户先点击或发送过消息，音频上下文才能恢复。

## 常见问题

- **Java 日志提示 provider 不可用**：检查 `ref-audio-path`、`text-lang`、`prompt-lang` 是否为空。
- **服务启动了但没声音**：先用上面的直接测试命令确认 GPT-SoVITS 能生成 wav，再看前端是否订阅了 `/api/tts/live`。
- **声音和 WebUI 不一样**：确认 Java 配置里的参考音频、prompt 文本、采样参数和 WebUI 当前参数一致。
- **延迟高**：调小 `tts.min-chunk-chars` / `tts.max-chunk-chars` 可以更快出首句，但请求会更碎。
