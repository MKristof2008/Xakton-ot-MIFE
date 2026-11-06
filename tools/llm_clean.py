"""LLM-постобработка транскрипта через локальную llama.cpp (Windows).

Принимает JSON в формате HEX: {"transcript":[{"speaker_id","start_ms","end_ms","text"}, ...]}
Возвращает тот же формат, но текст с расставленной пунктуацией и регистром.
Структура реплик (спикеры и таймкоды) НЕ меняется — LLM правит только поле text.
Режим «только пунктуация» выбран по A/B-замеру WER (см. docs/ML_MODELS.md):
полная правка текста ухудшала WER (+0.010 на диалоге dialog_2spk).
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]

DEFAULT_LLAMA = Path.home() / "llm" / "bin" / "llama-completion.exe"
DEFAULT_MODEL = Path.home() / "llm" / "qwen.gguf"

def run_llm(prompt: str, llama: Path, model: Path, max_tokens: int = 1024) -> str:
    import tempfile
    # llama-completion сам применяет chatml-шаблон модели.
    # Промпт передаём файлом: argv на Windows ломает UTF-8.
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".txt",
                                     delete=False) as f:
        f.write(prompt)
        prompt_file = f.name
    try:
        cmd = [
            str(llama), "-m", str(model),
            "--file", prompt_file,
            "-n", str(max_tokens),
            "--temp", "0.1",
            "--no-warmup",
        ]
        proc = subprocess.run(cmd, capture_output=True, timeout=1200)
    finally:
        Path(prompt_file).unlink(missing_ok=True)
    if proc.returncode != 0:
        raise RuntimeError(f"llama-completion failed: {proc.stderr[-500:]!r}")
    out = proc.stdout.decode("utf-8", errors="replace")
    idx = out.rfind("assistant")
    if idx != -1:
        out = out[idx + len("assistant"):]
    return out.replace("> EOF by user", "").strip()


def clean_line(text: str, llama: Path, model: Path) -> str:
    """Пунктуация и регистр одной строки. Слова не меняются."""
    prompt = (
        "Расставь знаки препинания и заглавные буквы в строке транскрипта. "
        "СТРОГО ЗАПРЕЩЕНО менять, добавлять, удалять или переставлять слова — "
        "только пунктуация и регистр. Ответь только исправленной строкой, "
        "без пояснений и кавычек.\n\n"
        "Строка: " + text + "\n\nИсправленная строка:"
    )
    out = run_llm(prompt, llama, model, max_tokens=max(128, 2 * len(text)))
    # Берём первую непустую строку ответа
    for line in out.splitlines():
        line = line.strip().strip('"«»')
        if line:
            return line
    return text


def clean_transcript(data: dict, llama: Path, model: Path) -> dict:
    out = {"transcript": []}
    for seg in data["transcript"]:
        cleaned_text = clean_line(seg["text"], llama, model)
        out["transcript"].append({
            "speaker_id": seg["speaker_id"],
            "start_ms": seg["start_ms"],
            "end_ms": seg["end_ms"],
            "text": cleaned_text,
        })
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description="LLM-постобработка HEX-транскрипта")
    ap.add_argument("input", help="входной JSON (HEX-формат)")
    ap.add_argument("-o", "--output", help="куда сохранить (по умолчанию stdout)")
    ap.add_argument("--llama", default=str(DEFAULT_LLAMA))
    ap.add_argument("--model", default=str(DEFAULT_MODEL))
    args = ap.parse_args()

    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    result = clean_transcript(data, Path(args.llama), Path(args.model))
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(text, encoding="utf-8")
        print(f"[llm_clean] сохранено: {args.output}")
    else:
        print(text)


if __name__ == "__main__":
    main()
