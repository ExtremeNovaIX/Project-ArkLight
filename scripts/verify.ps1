param(
    [ValidateSet("quick", "backend", "qt", "all")]
    [string]$Scope = "quick"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

function Invoke-Checked {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$FilePath exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if ($Scope -eq "quick" -or $Scope -eq "backend" -or $Scope -eq "all") {
    $backendDir = Join-Path $RepoRoot "backend"
    if ($Scope -eq "quick") {
        Invoke-Checked "mvn.cmd" @("-Dtest=RuntimeDoctorServiceTest,ApiContractDocumentTest,SttConfigTest,SttServerManagerTest,SttTranscriptEventTest,SttWebSocketProxyTest,SttStreamHandlerTest,SttGameIntentGateTest", "test") $backendDir
        $sherpaPython = Join-Path $RepoRoot "backend\runtime\asr\sherpa-qwen\.venv\Scripts\python.exe"
        $qwenPackPython = Join-Path $RepoRoot "backend\runtime\asr\custom\Qwen3_ASR\WPy64-312101\python\python.exe"
        if (Test-Path -LiteralPath $sherpaPython -PathType Leaf) {
            Invoke-Checked $sherpaPython @("-m", "unittest", "backend\tools\asr\test_sherpa_qwen_sidecar.py") $RepoRoot
        } elseif (Test-Path -LiteralPath $qwenPackPython -PathType Leaf) {
            Invoke-Checked $qwenPackPython @("-m", "unittest", "backend\tools\asr\test_sherpa_qwen_sidecar.py") $RepoRoot
        }
    } else {
        Invoke-Checked "mvn.cmd" @("test") $backendDir
    }
}

if ($Scope -eq "qt" -or $Scope -eq "all") {
    if (Get-Command "cmake" -ErrorAction SilentlyContinue) {
        Invoke-Checked "cmake" @("--build", "--preset", "qt-mingw-debug") $RepoRoot
    } else {
        Invoke-Checked "cmd.exe" @("/c", "build.bat") (Join-Path $RepoRoot "qt-frontend")
    }
}
