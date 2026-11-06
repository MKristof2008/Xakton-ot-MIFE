"""Замер WER/DER для выгрузки с телефона против эталона make_testdata."""
import json, io, re, sys

RU = [
 "Добрый день, коллеги. Начнём с обсуждения релиза мобильного приложения.",
 "Я думаю, мы успеваем выпустить версию в пятницу вечером.",
 "Хорошо. Тогда нужно подготовить описание изменений до четверга.",
 "Да, я подготовлю документ и пришлю всем участникам.",
 "Ещё остался открытым вопрос про поддержку старых телефонов.",
 "Мне кажется, этим стоит заняться уже после запуска.",
 "Согласен, но давай зафиксируем это как отдельную задачу.",
 "Хорошо, я запишу в план следующего спринта.",
]

def norm(t):
    return re.sub(r"[^\w\s]", " ", t.lower()).split()

def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    if not r: return 0.0
    prev = list(range(len(h)+1))
    for i, rw in enumerate(r, 1):
        cur = [i] + [0]*len(h)
        for j, hw in enumerate(h, 1):
            cur[j] = min(prev[j]+1, cur[j-1]+1, prev[j-1] + (rw != hw))
        prev = cur
    return prev[-1]/len(r)

def der(ref_turns, hyp, step=50):
    """DER по времени: доля мс с неверным спикером (сравнение меток кластеров после выравнивания)."""
    end = max(t["end"] for t in ref_turns)
    # выравниваем метки: сопоставляем по максимальному пересечению
    from collections import Counter
    pairs = Counter()
    for r in ref_turns:
        for h in hyp:
            ov = min(r["end"], h["end_ms"]/1000) - max(r["start"], h["start_ms"]/1000)
            if ov > 0: pairs[(r["speaker"], h["speaker_id"])] += ov
    mapping = {}
    for (rs, hs), _ in pairs.most_common():
        if rs not in mapping and hs not in mapping.values(): mapping[rs] = hs
    bad = tot = 0
    t = 0.0
    while t < end:
        rs = {r["speaker"] for r in ref_turns if r["start"] <= t < r["end"]}
        if rs:
            tot += step
            hs = {h["speaker_id"] for h in hyp if h["start_ms"]/1000 <= t < h["end_ms"]/1000}
            if {mapping.get(x) for x in rs} != hs: bad += step
        t += step/1000
    return bad/tot if tot else 0.0, mapping

truth = json.load(io.open(sys.argv[1], encoding="utf-8"))
out = json.load(io.open(sys.argv[2], encoding="utf-8"))["transcript"]
ref_text = " ".join(RU[i] for i in range(len(truth)//2) for _ in range(2))[:0]
# порядок: A phrase_i, B phrase_i
ref_text = " ".join(RU[i//2] for i in range(len(truth)))
hyp_text = " ".join(s["text"] for s in out)
print(f"WER  = {wer(ref_text, hyp_text):.3f}")
d, m = der(truth, out)
print(f"DER  = {d:.3f}   (спикеров: {len(set(s['speaker_id'] for s in out))}, выравнивание {m})")
print(f"реплик: {len(out)}")
