"""Сборка PDF-отчёта о составе и ролях проекта (русская кириллица через Arial)."""
from fpdf import FPDF

FONT_DIR = r"C:\Windows\Fonts"

SECTIONS = [
    ("Состав команды и роли", [
        "Проект: Xakton-ot-MIFE — пайплайн «аудио -> транскрипт -> протокол».",
        "",
        "1. Владелец проекта (MKristof2008) — роль: LLM и промпты",
        "   - Отвечает за промпт-инженерию: правила протоколиста, схемы JSON,",
        "     промпты очистки ASR, map-reduce, QA и определения спикеров",
        "     (prompts.py).",
        "   - Принимает решения по поведению модели и качеству протоколов.",
        "",
        "2. LLM-ассистент (Kimi Code CLI) — роль: код и интеграция",
        "   - Пишет и поддерживает код пайплайна: client.py, pipeline.py, app.py.",
        "   - Интеграция с AITunnel API, тестирование, мониторинг репозитория.",
        "",
        "3. AITunnel API (внешний сервис)",
        "   - LLM gpt-4o-mini для всех текстовых задач.",
        "   - Whisper (whisper-1) для транскрибации аудио.",
    ]),
    ("Промпт-система (prompts.py)", [
        "BASE_RULES — общие правила протоколиста: не выдумывать факты,",
        "сохранять таймкоды [MM:SS], писать на языке записи.",
        "",
        "CLEAN_PROMPT — исправление ошибок распознавания речи без",
        "перефразирования, с сохранением разметки [MM:SS] Спикер N.",
        "",
        "MINUTES_PROMPT — структурированный протокол в JSON: title, summary,",
        "topics, decisions, action_items (с owner и deadline), open_questions.",
        "Поручение фиксируется только при явном обязательстве.",
        "",
        "CHUNK_PROMPT + MERGE_PROMPT — map-reduce для длинных записей:",
        "протоколы фрагментов объединяются в итоговый без дублей.",
        "",
        "QA_PROMPT — ответы на вопросы строго по фрагментам транскрипта",
        "с таймкодами и спикерами; «В записи этого нет» — только если",
        "информации действительно нет.",
        "",
        "SPEAKER_NAMES_PROMPT — определение реальных имён спикеров",
        "по обращениям и представлениям; без догадок (null).",
    ]),
]


def main() -> None:
    pdf = FPDF()
    pdf.add_font("arial", "", f"{FONT_DIR}\\arial.ttf")
    pdf.add_font("arial", "B", f"{FONT_DIR}\\arialbd.ttf")
    pdf.add_page()
    pdf.set_auto_page_break(auto=True, margin=15)

    pdf.set_font("arial", "B", 16)
    pdf.cell(0, 12, "Xakton-ot-MIFE — состав команды и роли", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(4)

    for title, lines in SECTIONS:
        pdf.set_font("arial", "B", 13)
        pdf.cell(0, 9, title, new_x="LMARGIN", new_y="NEXT")
        pdf.set_font("arial", "", 11)
        for line in lines:
            if line:
                pdf.multi_cell(pdf.epw, 6, line)
            else:
                pdf.ln(3)
        pdf.ln(3)

    pdf.output("team_roles.pdf")
    print("team_roles.pdf создан")


if __name__ == "__main__":
    main()
