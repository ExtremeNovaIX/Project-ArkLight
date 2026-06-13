param(
    [string]$PythonVersion = "3.10.11",
    [string]$RuntimeRoot = "",
    [string]$ModelsRoot = "",
    [string]$Requirements = "",
    [string]$PipIndexUrl = "",
    [switch]$SkipModelDownload,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function FullPath([string]$Path) {
    return [System.IO.Path]::GetFullPath($Path)
}

function Download-File([string]$Uri, [string]$OutFile) {
    if ((Test-Path -LiteralPath $OutFile -PathType Leaf) -and ((Get-Item -LiteralPath $OutFile).Length -gt 0)) {
        Write-Host "Already downloaded: $OutFile"
        return
    }
    $parent = Split-Path -Parent $OutFile
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $tempFile = "$OutFile.tmp"
    Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
    Write-Host "Downloading: $Uri"
    $downloaded = $false
    for ($attempt = 1; $attempt -le 3 -and -not $downloaded; $attempt++) {
        try {
            Invoke-WebRequest -Uri $Uri -OutFile $tempFile
            $downloaded = $true
        } catch {
            Write-Warning "Download attempt $attempt failed: $($_.Exception.Message)"
            Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds (2 * $attempt)
        }
    }
    if (-not $downloaded) {
        $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($null -eq $curl) {
            throw "Download failed and curl.exe is not available: $Uri"
        }
        & $curl.Source -L --retry 5 --retry-delay 2 --fail -o $tempFile $Uri
        if ($LASTEXITCODE -ne 0) {
            Remove-Item -LiteralPath $tempFile -Force -ErrorAction SilentlyContinue
            throw "curl download failed with exit code $LASTEXITCODE"
        }
    }
    Move-Item -LiteralPath $tempFile -Destination $OutFile -Force
}

function Expand-TarBz2([string]$Archive, [string]$Destination) {
    Write-Host "Extracting: $Archive"
    if ([string]::IsNullOrWhiteSpace($script:ExtractorPython) -or -not (Test-Path -LiteralPath $script:ExtractorPython -PathType Leaf)) {
        throw "Python extractor is not available for $Archive"
    }
    $extractCode = @"
import pathlib
import sys
import tarfile

archive = pathlib.Path(sys.argv[1]).resolve()
destination = pathlib.Path(sys.argv[2]).resolve()
with tarfile.open(archive, 'r:bz2') as tar:
    for member in tar.getmembers():
        target = (destination / member.name).resolve()
        if destination not in (target, *target.parents):
            raise SystemExit('unsafe archive member: ' + member.name)
    tar.extractall(destination)
"@
    & $script:ExtractorPython -c $extractCode $Archive $Destination
    if ($LASTEXITCODE -ne 0) {
        throw "tar.bz2 extraction failed with exit code $LASTEXITCODE"
    }
}

$scriptRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($RuntimeRoot)) {
    $RuntimeRoot = FullPath (Join-Path $scriptRoot "..\..\runtime\asr\sherpa-qwen")
} else {
    $RuntimeRoot = FullPath $RuntimeRoot
}
if ([string]::IsNullOrWhiteSpace($ModelsRoot)) {
    $ModelsRoot = FullPath (Join-Path $RuntimeRoot "models")
} else {
    $ModelsRoot = FullPath $ModelsRoot
}
if ([string]::IsNullOrWhiteSpace($Requirements)) {
    $Requirements = FullPath (Join-Path $scriptRoot "requirements-sherpa-qwen.txt")
} else {
    $Requirements = FullPath $Requirements
}

$pythonBootstrap = FullPath (Join-Path $scriptRoot "..\python\bootstrap.ps1")
if (-not (Test-Path -LiteralPath $pythonBootstrap -PathType Leaf)) {
    throw "Python bootstrap not found: $pythonBootstrap"
}

$pythonBootstrapArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $pythonBootstrap,
    "-PythonVersion", $PythonVersion,
    "-RuntimeRoot", $RuntimeRoot,
    "-Requirements", $Requirements
)
if (-not [string]::IsNullOrWhiteSpace($PipIndexUrl)) {
    $pythonBootstrapArgs += @("-PipIndexUrl", $PipIndexUrl)
}
if ($Force) {
    $pythonBootstrapArgs += "-Force"
}
& powershell @pythonBootstrapArgs
if ($LASTEXITCODE -ne 0) {
    throw "Python bootstrap failed with exit code $LASTEXITCODE"
}

$pythonExe = Join-Path $RuntimeRoot ".venv\Scripts\python.exe"
$script:ExtractorPython = $pythonExe

New-Item -ItemType Directory -Force -Path $ModelsRoot | Out-Null
$qwenModelDir = Join-Path $ModelsRoot "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
$diarizationDir = Join-Path $ModelsRoot "diarization"
New-Item -ItemType Directory -Force -Path $diarizationDir | Out-Null

if (-not $SkipModelDownload) {
    $downloadDir = Join-Path $RuntimeRoot "downloads"
    New-Item -ItemType Directory -Force -Path $downloadDir | Out-Null

    if (-not (Test-Path -LiteralPath (Join-Path $qwenModelDir "conv_frontend.onnx") -PathType Leaf)) {
        $qwenArchive = Join-Path $downloadDir "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
        Download-File `
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2" `
            $qwenArchive
        Expand-TarBz2 $qwenArchive $ModelsRoot
    }

    $segmentationDir = Join-Path $diarizationDir "sherpa-onnx-pyannote-segmentation-3-0"
    if (-not (Test-Path -LiteralPath (Join-Path $segmentationDir "model.int8.onnx") -PathType Leaf)) {
        $segmentationArchive = Join-Path $downloadDir "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
        Download-File `
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" `
            $segmentationArchive
        Expand-TarBz2 $segmentationArchive $diarizationDir
    }

    $embeddingModel = Join-Path $diarizationDir "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
    if (-not (Test-Path -LiteralPath $embeddingModel -PathType Leaf)) {
        Download-File `
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" `
            $embeddingModel
    }
}

Write-Host "Verifying sherpa-qwen runtime imports..."
& $pythonExe -c "import sherpa_onnx, numpy, websockets; print('ok sherpa_onnx=' + getattr(sherpa_onnx, '__version__', 'unknown'))"
if ($LASTEXITCODE -ne 0) {
    throw "sherpa-qwen import verification failed with exit code $LASTEXITCODE"
}

Write-Host ""
Write-Host "ArcLight sherpa-qwen ASR runtime is ready:"
Write-Host "  Python: $pythonExe"
Write-Host "  Qwen3-ASR ONNX: $qwenModelDir"
Write-Host "  Diarization: $diarizationDir"
