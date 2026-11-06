"""A/B-замер WER для LLM-постобработки на диалоге dialog_2spk.

Эталонный текст собирается из PROMPTS тех же wav, что пошли в диалог
(тот же порядок, что и в tools/make_dialog.py).
Сравниваем: ASR-текст (dialog_2spk.hex.json) vs LLM-очищенный (dialog_2spk.llm.json).
"""
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))
from metrics import wer  # noqa: E402

ROOT = REPO_ROOT / "artifacts" / "test-audio"
SPEAKERS = ["1-20121125-pgp", "1981-20120705-haq"]
REPLICAS = 4


def load_prompts(speaker_dir: Path) -> dict[str, str]:
    prompts = {}
    for line in (speaker_dir / "etc" / "PROMPTS").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        m = re.match(r"\S+/(\S+)\s+(.*)", line)
        if m:
            prompts[m.group(1)] = m.group(2)
    return prompts


def reference_text() -> str:
    texts = []
    for i in range(REPLICAS):
        for spk in SPEAKERS:
            wavs = sorted((ROOT / spk / "wav").glob("*.wav"))
            name = wavs[i].stem
            texts.append(load_prompts(ROOT / spk)[name])
    return " ".join(texts)


def hypothesis_text(path: Path) -> str:
    data = json.loads(path.read_text(encoding="utf-8"))
    return " ".join(seg["text"] for seg in data["transcript"])


def main() -> None:
    ref = reference_text()
    asr = hypothesis_text(ROOT / "dialog_2spk.hex.json")
    llm_path = ROOT / "dialog_2spk.llm.json"
    wer_asr = wer(ref, asr)
    print(f"WER без LLM : {wer_asr:.4f}")
    if llm_path.exists():
        wer_llm = wer(ref, hypothesis_text(llm_path))
        print(f"WER с LLM   : {wer_llm:.4f}")
        delta = wer_llm - wer_asr
        print(f"Дельта      : {delta:+.4f} ({'LLM улучшила' if delta < 0 else 'LLM ухудшила/нейтрально'})")
    else:
        print("dialog_2spk.llm.json не найден — сначала прогони tools/llm_clean.py")


if __name__ == "__main__":
    main()
