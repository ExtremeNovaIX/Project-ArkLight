#!/usr/bin/env python3
import argparse
import subprocess
from pathlib import Path


def required_file(path: Path, label: str) -> None:
    if not path.is_file():
        raise SystemExit(f"missing {label}: {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Smoke-check sherpa-qwen ASR runtime and model files")
    repo_root = Path(__file__).resolve().parents[3]
    runtime_root = repo_root / "backend" / "runtime" / "asr" / "sherpa-qwen"
    parser.add_argument("--python", type=Path, default=runtime_root / ".venv" / "Scripts" / "python.exe")
    parser.add_argument(
        "--qwen-model-dir",
        type=Path,
        default=repo_root / "backend" / "runtime" / "asr" / "1.7B",
    )
    parser.add_argument("--diarization-model-dir", type=Path, default=runtime_root / "models" / "diarization")
    args = parser.parse_args()

    if not args.python.is_file():
        raise SystemExit(f"missing python runtime: {args.python}")
    required_file(args.qwen_model_dir / "conv_frontend.onnx", "conv_frontend")
    required_file(args.qwen_model_dir / "tokenizer" / "vocab.json", "tokenizer vocab")
    required_file(args.diarization_model_dir / "sherpa-onnx-pyannote-segmentation-3-0" / "model.int8.onnx",
                  "speaker segmentation model")
    required_file(args.diarization_model_dir / "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
                  "speaker embedding model")

    code = (
        "import importlib.util; "
        "missing=[n for n in ('sherpa_onnx','numpy','websockets') if importlib.util.find_spec(n) is None]; "
        "raise SystemExit(1 if missing else 0)"
    )
    result = subprocess.run([str(args.python), "-c", code], check=False)
    if result.returncode != 0:
        raise SystemExit("sherpa-qwen runtime imports failed")

    print("ok sherpa-qwen smoke inputs are present")


if __name__ == "__main__":
    main()
