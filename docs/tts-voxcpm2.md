# VoxCPM2 语音链路

这条链路只面向 [OpenBMB/VoxCPM](https://github.com/OpenBMB/VoxCPM) 的 VoxCPM2。Java 后端不加载模型，只调用一个本地 Python sidecar：

```text
Java /api/tts/live  <-  Java TTS provider  <-  http://127.0.0.1:8810/tts  <-  VoxCPM2 sidecar
```

## 适合什么时候用

VoxCPM2 更适合快速试音色设计、参考音频克隆、多语言或不想维护 GPT-SoVITS 训练链路的场景。它的模型较大，建议使用 CUDA 版 PyTorch。

## 推荐目录

推荐把环境、模型和参考音频都放在：

```text
E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2
```

一个常见结构是：

```text
backend\tts\runtime\VoxCPM2
├─ .venv\
├─ models\VoxCPM2\
│  ├─ config.json
│  ├─ model.safetensors
│  ├─ audiovae.pth
│  └─ tokenizer.json
└─ voices\
   └─ rossi.wav
```

## 安装环境

VoxCPM2 不要用 Python 3.14。建议 Python 3.10。

```bat
cd /d E:\PersonalProject\Project-1\backend
py -3.10 -m venv tts\runtime\VoxCPM2\.venv
tts\runtime\VoxCPM2\.venv\Scripts\python.exe -m pip install -U pip setuptools wheel
tts\runtime\VoxCPM2\.venv\Scripts\python.exe -m pip install voxcpm fastapi uvicorn soundfile
```

如果当前装到的是 CPU 版 PyTorch，需要换成 CUDA 版。以 CUDA 12.8 为例：

```bat
cd /d E:\PersonalProject\Project-1\backend
tts\runtime\VoxCPM2\.venv\Scripts\python.exe -m pip uninstall -y torch torchvision torchaudio
tts\runtime\VoxCPM2\.venv\Scripts\python.exe -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu128
```

验证 CUDA：

```bat
cd /d E:\PersonalProject\Project-1\backend
tts\runtime\VoxCPM2\.venv\Scripts\python.exe -c "import torch; print(torch.__version__); print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'NO CUDA')"
```

## 下载模型

Hugging Face 地址：

```text
https://huggingface.co/openbmb/VoxCPM2
```

用浏览器或下载器下载后，把文件放到：

```text
E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2\models\VoxCPM2
```

启动时使用本地路径，避免再次走 Hugging Face 下载。

## 启动 sidecar

默认情况下，切到 `provider: voxcpm2-http` 后，Java 启动时会按 `tts.vox-cpm2.runtime` 自动拉起 sidecar。
它等价于先进入：

```text
E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2
```

再执行：

```bat
.venv\Scripts\python.exe ..\..\tools\voxcpm2_tts_server.py --model-id models\VoxCPM2 --device cuda --host 127.0.0.1 --port 8810 --no-optimize
```

如果要手动启动，可以使用下面这条完整命令：

```bat
cd /d E:\PersonalProject\Project-1\backend
tts\runtime\VoxCPM2\.venv\Scripts\python.exe tools\voxcpm2_tts_server.py --model-id tts\runtime\VoxCPM2\models\VoxCPM2 --device cuda --host 127.0.0.1 --port 8810 --no-optimize
```

看到下面日志表示服务已启动：

```text
Uvicorn running on http://127.0.0.1:8810
```

健康检查：

```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8810/health
```

如果你选择手动启动 VoxCPM2，可以关闭 Java 自动启动：

```yaml
tts:
  vox-cpm2:
    runtime:
      auto-start-enabled: false
```

## Java 配置

配置文件：

```text
E:\PersonalProject\Project-1\config\application-tts.yaml
```

切到 VoxCPM2：

```yaml
tts:
  enabled: true
  provider: voxcpm2-http
  vox-cpm2:
    base-url: http://127.0.0.1:8810
    control-instruction: ''
    reference-wav-path: ''
    prompt-text: ''
    cfg-value: 2.0
    inference-timesteps: 10
    normalize: true
    denoise: false
    media-type: wav
    runtime:
      auto-start-enabled: true
      working-directory: tts/runtime/VoxCPM2
      health-path: /health
```

`normalize: true` 建议保持开启。中文里混入英文或数字时，关闭它可能出现异常短音频。

## 三种用法

### 1. 音色设计

不填参考音频，只用自然语言描述声音：

```yaml
tts:
  vox-cpm2:
    control-instruction: 年轻女性，中文，声音自然，语气轻快
    reference-wav-path: ''
    prompt-text: ''
```

### 2. 普通克隆

只填 `reference-wav-path`。这是推荐默认模式，稳定性最好：

```yaml
tts:
  vox-cpm2:
    control-instruction: ''
    reference-wav-path: E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2\voices\rossi.wav
    prompt-text: ''
```

### 3. Ultimate Clone

同时填参考音频和文本。这个模式更强，但要求 `prompt-text` 和参考音频内容严格一致；如果不一致，可能出现含混、伪中文或口音异常：

```yaml
tts:
  vox-cpm2:
    reference-wav-path: E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2\voices\rossi.wav
    prompt-text: 我是洛希娜，授名是狼魄，寓意是狼群的瑰宝。
```

## 直接测试

普通克隆测试：

```powershell
$body = @{
  text = "你好，我是洛希娜，现在开始测试克隆音色。"
  reference_wav_path = "E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2\voices\rossi.wav"
  cfg_value = 1.5
  inference_timesteps = 20
  normalize = $true
  denoise = $false
  media_type = "wav"
} | ConvertTo-Json

Invoke-WebRequest `
  -Uri http://127.0.0.1:8810/tts `
  -Method POST `
  -ContentType "application/json" `
  -Body $body `
  -OutFile E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2\clone-reference-only.wav
```

Ultimate Clone 测试：

```powershell
$body = @{
  text = "你好，我是洛希娜，现在开始测试克隆音色。"
  reference_wav_path = "E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2\voices\rossi.wav"
  prompt_text = "我是洛希娜，授名是狼魄，寓意是狼群的瑰宝。"
  cfg_value = 1.5
  inference_timesteps = 20
  normalize = $true
  denoise = $false
  media_type = "wav"
} | ConvertTo-Json

Invoke-WebRequest `
  -Uri http://127.0.0.1:8810/tts `
  -Method POST `
  -ContentType "application/json" `
  -Body $body `
  -OutFile E:\PersonalProject\Project-1\backend\tts\runtime\VoxCPM2\clone-ultimate.wav
```

## 常见问题

- **输出像方言或伪中文**：先去掉 `prompt_text`，改用普通克隆。Ultimate Clone 对文本和音频匹配要求很高。
- **输出只有 0 秒或很短**：检查是否开启 `normalize: true`，并避免在文本里混入未规范化的英文/数字串。
- **显卡没占用**：检查 PyTorch 是否 CUDA 版，运行上面的 CUDA 验证命令。
- **第一次启动很慢**：可以先保留 `--no-optimize`。确认能跑通后，再去掉它测试速度。
- **要开降噪**：启动 sidecar 时加 `--load-denoiser`，配置里再把 `denoise: true` 打开。
