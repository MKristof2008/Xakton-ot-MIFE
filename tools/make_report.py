"""Генерация официального DOCX-отчёта из docs/REPORT.md.

Оформление по ГОСТ Р 7.0.97-2016 (адаптировано): Times New Roman 14,
полуторный интервал, поля 3/1,5/2/2 см, нумерация страниц, титульный лист.
"""
import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs" / "REPORT.md"
OUTPUT = ROOT / "docs" / "REPORT.docx"

FONT = "Times New Roman"
FONT_SIZE = Pt(13)


# ---------- вспомогательные ----------

def set_cell_border(cell, **kwargs):
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    tcBorders = OxmlElement("w:tcBorders")
    for edge in ("top", "left", "bottom", "right"):
        if edge in kwargs:
            el = OxmlElement(f"w:{edge}")
            el.set(qn("w:val"), "single")
            el.set(qn("w:sz"), "4")
            el.set(qn("w:color"), "808080")
            tcBorders.append(el)
    tcPr.append(tcBorders)


def shade_cell(cell, fill="D9D9D9"):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), fill)
    tcPr.append(shd)


def add_page_number(paragraph):
    run = paragraph.add_run()
    fldChar1 = OxmlElement("w:fldChar")
    fldChar1.set(qn("w:fldCharType"), "begin")
    instrText = OxmlElement("w:instrText")
    instrText.set(qn("xml:space"), "preserve")
    instrText.text = "PAGE"
    fldChar2 = OxmlElement("w:fldChar")
    fldChar2.set(qn("w:fldCharType"), "end")
    run._r.append(fldChar1)
    run._r.append(instrText)
    run._r.append(fldChar2)


def style_document(doc):
    normal = doc.styles["Normal"]
    normal.font.name = FONT
    normal.font.size = FONT_SIZE
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)

    pf = normal.paragraph_format
    pf.line_spacing = 1.5
    pf.space_after = Pt(0)
    pf.first_line_indent = Cm(1.25)
    pf.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY

    for name, size, bold in (
        ("Heading 1", 16, True),
        ("Heading 2", 14, True),
        ("Heading 3", 13, True),
    ):
        st = doc.styles[name]
        st.font.name = FONT
        st.font.size = Pt(size)
        st.font.bold = bold
        st.font.color.rgb = RGBColor(0, 0, 0)
        st._element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
        st.paragraph_format.first_line_indent = Cm(0)
        st.paragraph_format.space_before = Pt(12)
        st.paragraph_format.space_after = Pt(6)
        st.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.LEFT

    for section in doc.sections:
        section.page_width = Cm(21)
        section.page_height = Cm(29.7)
        section.top_margin = Cm(2)
        section.bottom_margin = Cm(2)
        section.left_margin = Cm(3)
        section.right_margin = Cm(1.5)


def setup_page_numbers(doc):
    for section in doc.sections:
        footer = section.footer
        p = footer.paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.first_line_indent = Cm(0)
        run = p.add_run()
        run.font.name = FONT
        run.font.size = Pt(11)
        add_page_number(p)


# ---------- титульный лист ----------

def build_title_page(doc):
    def centered(text, size=13, bold=False, space_after=0, caps=False):
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.first_line_indent = Cm(0)
        p.paragraph_format.space_after = Pt(space_after)
        p.paragraph_format.line_spacing = 1.15
        run = p.add_run(text.upper() if caps else text)
        run.font.name = FONT
        run.font.size = Pt(size)
        run.font.bold = bold
        return p

    centered("МИНИСТЕРСТВО НАУКИ И ВЫСШЕГО ОБРАЗОВАНИЯ", 12)
    centered("РОССИЙСКОЙ ФЕДЕРАЦИИ", 12, space_after=6)
    centered("Федеральное государственное автономное образовательное учреждение", 11)
    centered("высшего образования", 11, space_after=6)
    centered("«Национальный исследовательский ядерный университет «МИФИ»»", 12, bold=True, space_after=6)
    centered("Инженерно-физический институт биомедицины", 11)
    centered("Программа бакалавриата 09.03.01 «Информатика и вычислительная техника»", 11)
    centered("Программа «Компьютерные системы и технологии» (ИВТ 2.0)", 11, space_after=60)

    centered("ОТЧЁТ", 24, bold=True, space_after=6)
    centered("о проделанной работе", 16, space_after=18)
    centered("по проекту «Нейро-стенографист»", 16, bold=True, space_after=6)
    centered("Хакатон Nuclear IT Hack, осенняя сессия 2026", 13, space_after=4)
    centered("Кейс компании Hex.Team", 13, space_after=60)

    # Исполнитель и проверяющий — справа
    for label, value in (
        ("Составитель (капитан команды, интегратор):", "Фатеев Ф. И."),
        ("Наименование команды:", "Hex.Team"),
        ("Репозиторий:", "MKristof2008/Xakton-ot-MIFE"),
        ("Период работ:", "6 ноября 2026 г."),
    ):
        p = doc.add_paragraph()
        p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
        p.paragraph_format.first_line_indent = Cm(0)
        p.paragraph_format.space_after = Pt(2)
        p.paragraph_format.line_spacing = 1.15
        r1 = p.add_run(label + " ")
        r1.font.name = FONT
        r1.font.size = Pt(12)
        r2 = p.add_run(value)
        r2.font.name = FONT
        r2.font.size = Pt(12)
        r2.font.bold = True

    for _ in range(6):
        doc.add_paragraph()

    centered("Москва, 2026", 13, bold=True)

    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


# ---------- разбор markdown ----------

def split_table(lines):
    """Возвращает (заголовки, строки) или None."""
    rows = [l for l in lines if l.strip().startswith("|")]
    if len(rows) < 2:
        return None

    def cells(line):
        parts = line.strip().strip("|").split("|")
        return [c.strip() for c in parts]

    header = cells(rows[0])
    sep_idx = 1
    if not re.match(r"^[\s:|-]+$", rows[sep_idx]):
        return None
    body = [cells(r) for r in rows[sep_idx + 1:]]
    return header, body


def add_table(doc, header, body):
    table = doc.add_table(rows=1, cols=len(header))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False

    # Ширины колонок пропорционально объёму текста: узкие «№», широкие описания.
    measured = []
    for i in range(len(header)):
        values = [header[i]] + [r[i] for r in body if i < len(r)]
        longest = max(len(v) for v in values)
        avg = sum(len(v) for v in values) / max(len(values), 1)
        weight = max(longest * 0.45 + avg * 0.55, 4)

        # Заголовок не должен переноситься посреди слова: гарантируем ширину
        # по самому длинному слову в шапке. Жирный шрифт шире обычного,
        # поэтому запас увеличен.
        header_word = max((len(w) for w in header[i].split()), default=1)
        body_word = max(
            (len(w) for r in body if i < len(r) for w in r[i].split()),
            default=1,
        )
        weight = max(weight, header_word * 1.45, body_word * 1.15)
        measured.append(weight)

    total_weight = sum(measured)
    usable_cm = 16.5  # ширина полосы набора при полях 3 и 1,5 см
    widths = [Cm(usable_cm * w / total_weight) for w in measured]

    hdr = table.rows[0].cells
    for i, text in enumerate(header):
        hdr[i].text = ""
        p = hdr[i].paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        p.paragraph_format.first_line_indent = Cm(0)
        p.paragraph_format.line_spacing = 1.0
        p.paragraph_format.space_after = Pt(2)
        run = p.add_run(clean_inline(text))
        run.font.name = FONT
        run.font.size = Pt(11)
        run.font.bold = True
        shade_cell(hdr[i], "E7E6E6")
        set_cell_border(hdr[i])

    # по ГОСТ: заголовок повторяется на каждой странице
    repeat_header(table.rows[0])
    # строки не разрываются между страницами
    no_row_split(table)

    for row in body:
        cells = table.add_row().cells
        for i, text in enumerate(row):
            if i >= len(cells):
                break
            cells[i].text = ""
            p = cells[i].paragraphs[0]
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT
            p.paragraph_format.first_line_indent = Cm(0)
            p.paragraph_format.line_spacing = 1.0
            p.paragraph_format.space_after = Pt(2)
            run = p.add_run(clean_inline(text))
            run.font.name = FONT
            run.font.size = Pt(11)
            set_cell_border(cells[i])

    # применяем ширины ко всем колонкам
    for row in table.rows:
        for i, cell in enumerate(row.cells):
            if i < len(widths):
                cell.width = widths[i]

    doc.add_paragraph().paragraph_format.space_after = Pt(6)


def clean_inline(text):
    """Убирает markdown-разметку, оставляя текст."""
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text)
    text = re.sub(r"`(.+?)`", r"\1", text)
    text = re.sub(r"\[(.+?)\]\((.+?)\)", r"\1", text)
    return text.strip()


def repeat_header(row):
    """Повторяет строку заголовка на каждой странице (требование ГОСТ)."""
    trPr = row._tr.get_or_add_trPr()
    tblHeader = OxmlElement("w:tblHeader")
    tblHeader.set(qn("w:val"), "true")
    trPr.append(tblHeader)


def no_row_split(table):
    """Запрещает разрыв строки таблицы между страницами."""
    for row in table.rows:
        trPr = row._tr.get_or_add_trPr()
        cantSplit = OxmlElement("w:cantSplit")
        cantSplit.set(qn("w:val"), "true")
        trPr.append(cantSplit)


def add_rich_text(paragraph, text, size=FONT_SIZE):
    """Добавляет текст в абзац, сохраняя **жирный** и `код`."""
    pattern = re.compile(r"(\*\*.+?\*\*|`.+?`)")
    for chunk in pattern.split(text):
        if not chunk:
            continue
        bold = chunk.startswith("**") and chunk.endswith("**")
        code = chunk.startswith("`") and chunk.endswith("`")
        value = chunk[2:-2] if bold else (chunk[1:-1] if code else chunk)

        run = paragraph.add_run(value)
        run.font.name = "Courier New" if code else FONT
        run.font.size = Pt(size.pt - 1) if code else size
        run.font.bold = bold


# ---------- основной разбор ----------

def build_body(doc, text):
    lines = text.split("\n")
    i = 0
    title_skipped = False

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        # горизонтальная линия
        if re.match(r"^-{3,}$", stripped):
            i += 1
            continue

        # таблица
        if stripped.startswith("|"):
            block = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                block.append(lines[i])
                i += 1
            parsed = split_table(block)
            if parsed:
                add_table(doc, *parsed)
            continue

        # блок кода
        if stripped.startswith("```"):
            i += 1
            code = []
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code.append(lines[i])
                i += 1
            i += 1
            p = doc.add_paragraph()
            p.paragraph_format.first_line_indent = Cm(0)
            p.paragraph_format.left_indent = Cm(1)
            p.paragraph_format.line_spacing = 1.0
            p.paragraph_format.space_before = Pt(6)
            p.paragraph_format.space_after = Pt(6)
            run = p.add_run("\n".join(code))
            run.font.name = "Courier New"
            run.font.size = Pt(10)
            continue

        # заголовки
        m = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if m:
            level = len(m.group(1))
            text_h = clean_inline(m.group(2))

            if level == 1 and not title_skipped:
                title_skipped = True
                i += 1
                continue

            if level == 2 and text_h.startswith("по проекту"):
                i += 1
                continue

            if level <= 3:
                doc.add_heading(text_h, level=min(level, 3))
            else:
                p = doc.add_paragraph()
                p.paragraph_format.first_line_indent = Cm(0)
                run = p.add_run(text_h)
                run.font.bold = True
                run.font.name = FONT
                run.font.size = FONT_SIZE
            i += 1
            continue

        # маркированный список
        if re.match(r"^[-*]\s+", stripped):
            p = doc.add_paragraph()
            p.paragraph_format.first_line_indent = Cm(0)
            p.paragraph_format.left_indent = Cm(1.25)
            p.paragraph_format.space_after = Pt(0)
            run = p.add_run("— " + clean_inline(re.sub(r"^[-*]\s+", "", stripped)))
            run.font.name = FONT
            run.font.size = FONT_SIZE
            i += 1
            continue

        # обычный абзац: склеиваем соседние строки до пустой строки или разметки
        parts = [stripped]
        i += 1
        while i < len(lines):
            nxt = lines[i].strip()
            if (not nxt or nxt.startswith("|") or nxt.startswith("```")
                    or re.match(r"^#{1,4}\s", nxt) or re.match(r"^[-*]\s+", nxt)
                    or re.match(r"^-{3,}$", nxt)):
                break
            parts.append(nxt)
            i += 1

        p = doc.add_paragraph()
        add_rich_text(p, " ".join(parts))


def main():
    if not SOURCE.exists():
        sys.exit(f"Не найден источник: {SOURCE}")

    doc = Document()
    style_document(doc)
    build_title_page(doc)
    setup_page_numbers(doc)

    text = SOURCE.read_text(encoding="utf-8")
    build_body(doc, text)

    doc.save(OUTPUT)
    print(f"Создан: {OUTPUT}")
    print(f"Размер: {OUTPUT.stat().st_size / 1024:.0f} КБ")


if __name__ == "__main__":
    main()