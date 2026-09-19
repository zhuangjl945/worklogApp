# -*- coding: utf-8 -*-
"""把 Markdown 版申请报告转换为符合党政机关公文格式的 Word 文档（GB/T 9704 版式）。"""
import re, sys
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn

SRC = r"D:\Cursors\worklog\docs\医院采购灾备服务器申请报告.md"
OUT = r"D:\Cursors\worklog\docs\医院采购灾备服务器申请报告.docx"

FS = {"song": "宋体", "fangsong": "仿宋", "heiti": "黑体", "kaiti": "楷体"}

def set_font(run, name, size, bold=False, color=None):
    run.font.name = name
    run.font.size = Pt(size)
    run.bold = bold
    if color:
        run.font.color.rgb = RGBColor(*color)
    rpr = run._element.get_or_add_rPr()
    rf = rpr.find(qn("w:rFonts"))
    if rf is None:
        rf = rpr.makeelement(qn("w:rFonts"), {})
        rpr.append(rf)
    rf.set(qn("w:eastAsia"), name)
    rf.set(qn("w:ascii"), name)
    rf.set(qn("w:hAnsi"), name)

BOLD_RE = re.compile(r"\*\*(.+?)\*\*")

def add_text(p, text, name, size, bold=False, color=None):
    """支持 **加粗** 行内标记。"""
    pos = 0
    for m in BOLD_RE.finditer(text):
        if m.start() > pos:
            set_font(p.add_run(text[pos:m.start()]), name, size, bold, color)
        set_font(p.add_run(m.group(1)), name, size, True, color)
        pos = m.end()
    if pos < len(text):
        set_font(p.add_run(text[pos:]), name, size, bold, color)

def para(doc, text, name="fangsong", size=16, indent=True, align=None,
         space_before=0, space_after=0, line=28, color=None, bold=False):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.line_spacing_rule = WD_LINE_SPACING.EXACTLY
    pf.line_spacing = Pt(line)
    pf.space_before = Pt(space_before)
    pf.space_after = Pt(space_after)
    if indent:
        pf.first_line_indent = Pt(size * 2)
    if align:
        p.alignment = align
    add_text(p, text, FS[name], size, bold, color)
    return p

def add_table(doc, rows):
    """rows: 已去掉分隔行的单元格二维数组。"""
    ncol = max(len(r) for r in rows)
    t = doc.add_table(rows=len(rows), cols=ncol)
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, row in enumerate(rows):
        for j in range(ncol):
            cell = t.cell(i, j)
            cell.text = ""
            p = cell.paragraphs[0]
            p.paragraph_format.line_spacing_rule = WD_LINE_SPACING.SINGLE
            p.paragraph_format.space_before = Pt(1)
            p.paragraph_format.space_after = Pt(1)
            txt = row[j] if j < len(row) else ""
            add_text(p, txt, "宋体" if i else FS["heiti"], 9, bold=(i == 0))
            if i == 0:
                p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    para(doc, "", size=8, indent=False, line=12)
    return t

def page_break(doc):
    from docx.enum.text import WD_BREAK
    r = doc.add_paragraph().add_run()
    r.add_break(WD_BREAK.PAGE)

def build():
    with open(SRC, encoding="utf-8-sig") as f:
        lines = f.read().split("\n")

    doc = Document()
    s = doc.sections[0]
    s.page_height, s.page_width = Cm(29.7), Cm(21.0)
    s.top_margin, s.bottom_margin = Cm(3.7), Cm(3.5)
    s.left_margin, s.right_margin = Cm(2.8), Cm(2.6)
    st = doc.styles["Normal"]
    st.font.name = FS["fangsong"]
    st.font.size = Pt(16)
    st.element.rPr.rFonts.set(qn("w:eastAsia"), FS["fangsong"])

    i, n = 0, len(lines)
    first_h1 = True
    while i < n:
        raw = lines[i].rstrip()
        line = raw.strip()
        # 表格块
        if line.startswith("|"):
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{2,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            add_table(doc, rows)
            continue
        if not line:
            i += 1
            continue
        if line.startswith("---"):
            i += 1
            continue
        if line.startswith("# "):
            para(doc, line[2:], "song", 22, indent=False,
                 align=WD_ALIGN_PARAGRAPH.CENTER, space_after=18, line=36, bold=True)
            i += 1
            continue
        if line.startswith("## "):
            txt = line[3:]
            if txt.startswith("附：一页纸"):
                page_break(doc)
            para(doc, txt, "heiti", 16, indent=True, space_before=6, space_after=3)
            i += 1
            continue
        if line.startswith("### "):
            para(doc, line[4:], "kaiti", 16, indent=True, space_before=3)
            i += 1
            continue
        if line.startswith("> "):
            para(doc, line[2:], "kaiti", 12, indent=False, color=(0x60, 0x60, 0x60), line=18)
            i += 1
            continue
        m = re.match(r"^([-*])\s+(.*)$", line)
        if m:
            para(doc, "—" + m.group(2), "fangsong", 16)
            i += 1
            continue
        # 正文（含 1. 2. 有序项，保留原编号文字）
        size = 16
        if line.startswith("注：") or line.startswith("说明：") or line.startswith("申报科室") or line.startswith("主送") or line.startswith("抄送"):
            size = 14
        para(doc, line, "fangsong", size)
        i += 1

    doc.save(OUT)
    print("saved:", OUT)

build()
