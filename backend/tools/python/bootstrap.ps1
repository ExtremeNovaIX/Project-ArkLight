param(
    [string]$PythonVersion = "3.10.11",
    [string]$RuntimeRoot = "",
    [string]$Requirements = "",
    [string]$PipIndexUrl = "",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function FullPath([string]$Path) {
    return [System.IO.Path]::GetFullPath($Path)
}

function Resolve-Uv {
    $existing = Get-Command uv -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        return $existing.Source
    }

    Write-Host "uv not found. Installing uv for the current user..."
    $installer = Join-Path ([System.IO.Path]::GetTempPath()) "uv-install.ps1"
    Invoke-WebRequest -Uri "https://astral.sh/uv/install.ps1" -OutFile $installer
    powershell -NoProfile -ExecutionPolicy Bypass -File $installer

    $candidates = @(
        (Join-Path $env:USERPROFILE ".local\bin\uv.exe"),
        (Join-Path $env:USERPROFILE ".cargo\bin\uv.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    throw "uv was installed but uv.exe was not found. Open a new terminal or add uv to PATH."
}

function Resolve-ExistingPython {
    $script = "import sys; print(str(sys.version_info.major) + '.' + str(sys.version_info.minor) + '|' + sys.executable)"

    function TryPython([string]$Command, [string[]]$Args) {
        try {
            $outputLines = @(& $Command @Args -c $script 2>$null | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($LASTEXITCODE -ne 0 -or $outputLines.Count -eq 0) {
                return ""
            }
            $output = $outputLines[$outputLines.Count - 1]
            $parts = "$output".Trim() -split "\|", 2
            if ($parts.Length -ne 2) {
                return ""
            }
            $version = [version]$parts[0]
            if ($version -ge [version]"3.10" -and $version -lt [version]"3.13") {
                return $parts[1]
            }
        } catch {
            return ""
        }
        return ""
    }

    $pyLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($null -ne $pyLauncher) {
        foreach ($versionArg in @("-3.10", "-3.11", "-3.12")) {
            $candidate = TryPython $pyLauncher.Source @($versionArg)
            if (-not [string]::IsNullOrWhiteSpace($candidate)) {
                return $candidate
            }
        }
    }

    foreach ($commandName in @("python3.10", "python3", "python")) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($null -ne $command) {
            $candidate = TryPython $command.Source @()
            if (-not [string]::IsNullOrWhiteSpace($candidate)) {
                return $candidate
            }
        }
    }

    return ""
}

$scriptRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($RuntimeRoot)) {
    $RuntimeRoot = FullPath (Join-Path $scriptRoot "..\..\runtime\python")
} else {
    $RuntimeRoot = FullPath $RuntimeRoot
}
if ([string]::IsNullOrWhiteSpace($Requirements)) {
    $Requirements = FullPath (Join-Path $scriptRoot "requirements-stt.txt")
} else {
    $Requirements = FullPath $Requirements
}

if (-not (Test-Path -LiteralPath $Requirements -PathType Leaf)) {
    throw "Requirements file not found: $Requirements"
}

$venvDir = Join-Path $RuntimeRoot ".venv"
$pythonExe = Join-Path $venvDir "Scripts\python.exe"

if ($Force -and (Test-Path -LiteralPath $venvDir)) {
    Remove-Item -LiteralPath $venvDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null
$uvCacheDir = Join-Path $RuntimeRoot "uv-cache"
$uvPythonDir = Join-Path $RuntimeRoot "uv-python"
New-Item -ItemType Directory -Force -Path $uvCacheDir | Out-Null
New-Item -ItemType Directory -Force -Path $uvPythonDir | Out-Null
$env:UV_CACHE_DIR = $uvCacheDir

$uv = Resolve-Uv

Write-Host "Installing managed Python $PythonVersion..."
$projectPythonDisabledMarker = Join-Path $uvPythonDir ".project-install-unavailable"
$pythonForVenv = $PythonVersion
if (-not (Test-Path -LiteralPath $projectPythonDisabledMarker -PathType Leaf)) {
    & $uv python install $PythonVersion --install-dir $uvPythonDir --cache-dir $uvCacheDir --no-registry --force
    if ($LASTEXITCODE -eq 0) {
        $env:UV_PYTHON_INSTALL_DIR = $uvPythonDir
    } else {
        Write-Warning "uv project-local managed Python install failed with exit code $LASTEXITCODE."
        "Project-local uv Python install failed at $(Get-Date -Format o). Falling back to uv default install dir." |
            Set-Content -LiteralPath $projectPythonDisabledMarker -Encoding UTF8
    }
}

if (Test-Path -LiteralPath $projectPythonDisabledMarker -PathType Leaf) {
    Remove-Item Env:UV_PYTHON_INSTALL_DIR -ErrorAction SilentlyContinue
    Write-Warning "Using uv default managed Python install dir because project-local install is unavailable."
    & $uv python install $PythonVersion --cache-dir $uvCacheDir --no-registry --force
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "uv default managed Python install failed with exit code $LASTEXITCODE."
        $fallbackPython = Resolve-ExistingPython
        $fallbackPython = "$fallbackPython".Trim()
        if ([string]::IsNullOrWhiteSpace($fallbackPython)) {
            throw "uv python install failed and no existing Python 3.10-3.12 interpreter was found."
        }
        Write-Warning "Falling back to existing Python for venv creation: $fallbackPython"
        $pythonForVenv = $fallbackPython
    }
} else {
    $pythonForVenv = $PythonVersion
}

Write-Host "Creating venv: $venvDir"
$uvVenvDisabledMarker = Join-Path $RuntimeRoot ".uv-venv-unavailable"
$uvVenvSucceeded = $false
if ((Test-Path -LiteralPath $pythonExe -PathType Leaf) -and -not $Force) {
    Write-Host "Using existing venv: $venvDir"
    $uvVenvSucceeded = $true
} elseif (-not (Test-Path -LiteralPath $uvVenvDisabledMarker -PathType Leaf)) {
    & $uv venv $venvDir --python $pythonForVenv --seed --cache-dir $uvCacheDir
    if ($LASTEXITCODE -eq 0) {
        $uvVenvSucceeded = $true
    } else {
        Write-Warning "uv venv failed with exit code $LASTEXITCODE. Falling back to python -m venv."
        "uv venv failed at $(Get-Date -Format o). Falling back to python -m venv." |
            Set-Content -LiteralPath $uvVenvDisabledMarker -Encoding UTF8
    }
} else {
    Write-Warning "Using python -m venv because uv venv is marked unavailable for this runtime."
}

if (-not $uvVenvSucceeded) {
    $venvSourcePython = $pythonForVenv
    if (-not (Test-Path -LiteralPath $venvSourcePython -PathType Leaf)) {
        $venvSourcePython = Resolve-ExistingPython
        $venvSourcePython = "$venvSourcePython".Trim()
    }
    if ([string]::IsNullOrWhiteSpace($venvSourcePython) -or -not (Test-Path -LiteralPath $venvSourcePython -PathType Leaf)) {
        throw "No usable Python interpreter was found for python -m venv fallback."
    }
    & $venvSourcePython -m venv --clear $venvDir
    if ($LASTEXITCODE -ne 0) {
        throw "python -m venv failed with exit code $LASTEXITCODE"
    }
}

Write-Host "Installing shared lightweight Python dependencies from $Requirements"
$env:PIP_DISABLE_PIP_VERSION_CHECK = "1"
$pipArgs = @("install", "--disable-pip-version-check")
if (-not [string]::IsNullOrWhiteSpace($PipIndexUrl)) {
    $pipArgs += @("-i", $PipIndexUrl)
}
$pipArgs += @("-r", $Requirements)
& $pythonExe -m pip @pipArgs
if ($LASTEXITCODE -ne 0) {
    throw "pip install failed with exit code $LASTEXITCODE"
}

Write-Host "Verifying shared Python imports..."
& $pythonExe -c "import numpy; print('ok numpy=' + numpy.__version__)"
if ($LASTEXITCODE -ne 0) {
    throw "Shared Python verification failed with exit code $LASTEXITCODE"
}

Write-Host ""
Write-Host "Arklight Python runtime is ready:"
Write-Host "  $pythonExe"
