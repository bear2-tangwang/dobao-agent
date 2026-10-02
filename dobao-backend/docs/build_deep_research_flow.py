# -*- coding: utf-8 -*-
"""生成《深度研究流程简图》JPG（高层流程，不展开代码细节）。"""
import math
import os

from PIL import Image, ImageDraw, ImageFont

SCALE = 2
W, H = 1080, 1880
CX, BW = 380, 470          # 主链
RX, RW = 855, 330          # 右侧分支
FONT_REG = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
if not os.path.exists(FONT_BOLD):
    FONT_BOLD = FONT_REG

INK = (88, 105, 124)
GRAY = (110, 128, 146)
ORANGE = (224, 123, 26)
GOLD = (217, 164, 65)

FILL = {
    "start":  ((255, 244, 214), (217, 164, 65), (90, 61, 0)),
    "entry":  ((227, 242, 253), (30, 111, 184), (13, 59, 102)),
    "step":   ((234, 247, 236), (61, 154, 80), (18, 64, 29)),
    "model":  ((238, 244, 255), (74, 126, 187), (18, 38, 58)),
    "plan":   ((255, 240, 224), (224, 123, 26), (92, 47, 0)),
    "done":   ((227, 242, 253), (30, 111, 184), (13, 59, 102)),
    "stop":   ((246, 247, 249), (135, 148, 165), (38, 49, 61)),
}
_cache = {}


def font(size, bold=False):
    k = (size, bold)
    if k not in _cache:
        _cache[k] = ImageFont.truetype(FONT_BOLD if bold else FONT_REG, int(round(size * SCALE)))
    return _cache[k]


def meas(t, size, bold=False):
    b = font(size, bold).getbbox(t)
    return (b[2] - b[0]) / SCALE


NODES = {}


def node(key, title, sub, top, klass, cx=CX, w=BW, h=None, ts=17, ss=13):
    if h is None:
        h = 46 + (26 if sub else 0)
    NODES[key] = dict(key=key, title=title, sub=sub, top=top, klass=klass,
                      cx=cx, w=w, h=h, ts=ts, ss=ss)
    return top + h


# ============================ 主链 ============================
y = 104
y = node("ask", "用户提出研究问题", None, y, "start", h=54)
y = node("req", "请求接入与校验", "SSE 建立长连接，参数校验", y + 22, "entry")
y = node("ctx", "装配会话与上下文", "加载历史记忆、保存提问", y + 22, "entry")
y = node("clarify", "需求澄清", "判断信息是否足够", y + 22, "model")

# 分支：需要补充信息
node("pause", "暂停研究", "向用户提问补充信息", NODES["clarify"]["top"] + 6, "stop", cx=RX, w=RW)

y = NODES["clarify"]["top"] + NODES["clarify"]["h"] + 44
y = node("topic", "生成研究主题", "拆出 3~5 个分析方向", y, "step")

# ---- 循环体 ----
LOOP_TOP = y + 34
ly = LOOP_TOP + 52
ly = node("plan", "拆分任务", "按依赖排出检索计划", ly, "plan")
ly = node("exec", "并行检索", "多线程联网取证据", ly + 22, "step")
ly = node("judge", "成果评估", "信息够不够、结论站不站得住", ly + 22, "plan")
LOOP_BOTTOM = ly + 12

y = LOOP_BOTTOM + 54
y = node("report", "生成研究报告", "只依据检索到的事实成文", y, "model")
y = node("push", "推送前端", "思考过程 · 正文 · 参考来源", y + 22, "done")
y = node("save", "保存结果并清理", "结果落库，释放所有资源", y + 22, "step")
H = y + 66

img = Image.new("RGB", (int(W * SCALE), int(H * SCALE)), (255, 255, 255))
d = ImageDraw.Draw(img)


def S(v):
    return v * SCALE


def arrow(pts, dash=False, label=None, lpos=None, lrot=False, anchor="lm"):
    p = [(S(x), S(y)) for (x, y) in pts]
    if dash:
        for i in range(len(p) - 1):
            seg_dash(d, p[i], p[i + 1])
    else:
        d.line(p, fill=INK, width=max(1, int(round(1.8 * SCALE))), joint="curve")
    head(p[-2], p[-1])
    if label and lpos:
        x, y = lpos
        if lrot:
            rotate_text(label, x, y)
        else:
            d.text((S(x), S(y)), label, font=font(13, True), fill=(66, 86, 107), anchor=anchor)


def seg_dash(d, a, b, on=8, off=6, w=1.8, color=(140, 152, 166)):
    tot = math.hypot(b[0] - a[0], b[1] - a[1])
    if tot == 0:
        return
    ux, uy = (b[0] - a[0]) / tot, (b[1] - a[1]) / tot
    pos = 0.0
    while pos < tot:
        end = min(pos + S(on), tot)
        d.line([(a[0] + ux * pos, a[1] + uy * pos), (a[0] + ux * end, a[1] + uy * end)],
               fill=color, width=max(1, int(round(w * SCALE))))
        pos = end + S(off)


def head(a, b, color=INK):
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    L, Wd = S(12), S(5.5)
    d.polygon([b,
               (b[0] - L * math.cos(ang) + Wd * math.sin(ang), b[1] - L * math.sin(ang) - Wd * math.cos(ang)),
               (b[0] - L * math.cos(ang) - Wd * math.sin(ang), b[1] - L * math.sin(ang) + Wd * math.cos(ang))],
              fill=color)


def rotate_text(text, x, y):
    tmp = Image.new("RGBA", (int(meas(text, 13, True) * SCALE) + 16 * SCALE, 26 * SCALE), (0, 0, 0, 0))
    ImageDraw.Draw(tmp).text((8 * SCALE, 13 * SCALE), text, font=font(13, True),
                             fill=(66, 86, 107), anchor="lm")
    tmp = tmp.rotate(90, expand=True)
    img.paste(tmp, (int(S(x) - tmp.width / 2), int(S(y) - tmp.height / 2)), tmp)


def box(n):
    fill, line, txt = FILL[n["klass"]]
    x1, y1 = S(n["cx"] - n["w"] / 2), S(n["top"])
    x2, y2 = S(n["cx"] + n["w"] / 2), S(n["top"] + n["h"])
    d.rounded_rectangle([x1 + S(1), y1 + S(3), x2 + S(1), y2 + S(3)], radius=S(13), fill=(216, 222, 230))
    d.rounded_rectangle([x1, y1, x2, y2], radius=S(13), fill=fill, outline=line, width=max(1, int(round(1.9 * SCALE))))
    ty = n["top"] + (n["h"] / 2 - 11 if n["sub"] else n["h"] / 2)
    d.text((S(n["cx"]), S(ty)), n["title"], font=font(n["ts"], True), fill=txt, anchor="mm")
    if n["sub"]:
        d.text((S(n["cx"]), S(ty + 25)), n["sub"], font=font(n["ss"]), fill=GRAY, anchor="mm")


def T(k, side, dx=0.0):
    n = NODES[k]
    return {"T": (n["cx"] + dx, n["top"]), "B": (n["cx"] + dx, n["top"] + n["h"]),
            "L": (n["cx"] - n["w"] / 2, n["top"] + n["h"] / 2),
            "R": (n["cx"] + n["w"] / 2, n["top"] + n["h"] / 2)}[side]


# ============================ 标题 ============================
d.text((S(W / 2), S(46)), "深度研究流程", font=font(32, True), fill=(13, 59, 102), anchor="mm")
d.text((S(W / 2), S(78)), "用户提问 → 澄清 → 主题 → 计划-执行-评估循环（≤ 3 轮）→ 研究报告",
       font=font(14.5), fill=GRAY, anchor="mm")

# ============================ 循环体虚线框 ============================
lx1, lx2 = 86, CX + BW / 2 + 6
seg_dash(d, (S(lx1), S(LOOP_TOP)), (S(lx2), S(LOOP_TOP)), on=13, off=8, w=2.4, color=ORANGE)
seg_dash(d, (S(lx1), S(LOOP_BOTTOM)), (S(lx2), S(LOOP_BOTTOM)), on=13, off=8, w=2.4, color=ORANGE)
seg_dash(d, (S(lx1), S(LOOP_TOP)), (S(lx1), S(LOOP_BOTTOM)), on=13, off=8, w=2.4, color=ORANGE)
seg_dash(d, (S(lx2), S(LOOP_TOP)), (S(lx2), S(LOOP_BOTTOM)), on=13, off=8, w=2.4, color=ORANGE)
d.rounded_rectangle([S(lx1 + 14), S(LOOP_TOP - 15), S(lx1 + 254), S(LOOP_TOP + 15)], radius=S(8),
                    fill=(255, 240, 224), outline=ORANGE, width=max(1, int(round(1.5 * SCALE))))
d.text((S(lx1 + 134), S(LOOP_TOP)), "研究循环（最多 3 轮）", font=font(14, True), fill=(138, 74, 0), anchor="mm")

# ============================ 箭头 ============================
arrow([T("ask", "B"), T("req", "T")])
arrow([T("req", "B"), T("ctx", "T")])
arrow([T("ctx", "B"), T("clarify", "T")])
arrow([T("clarify", "R"), (700, T("clarify", "R")[1]), (700, T("pause", "T")[1] - 22),
       (T("pause", "T")[0], T("pause", "T")[1] - 22), T("pause", "T")], label="信息不足", lpos=(614, T("clarify", "R")[1] - 12))
arrow([T("clarify", "B"), T("topic", "T")], label="信息充足", lpos=(CX + 14, (T("clarify", "B")[1] + T("topic", "T")[1]) / 2 + 2))
arrow([T("topic", "B"), T("plan", "T")])
arrow([T("plan", "B"), T("exec", "T")])
arrow([T("exec", "B"), T("judge", "T")])
# 回灌：右侧绕回
back_x = lx2 - 20
arrow([T("judge", "R"), (back_x, T("judge", "R")[1]), (back_x, T("plan", "R")[1]), T("plan", "R")],
      dash=True, label="未通过", lpos=(back_x + 26, (T("plan", "R")[1] + T("judge", "R")[1]) / 2 + 30))
arrow([T("judge", "B"), T("report", "T")], label="通过 / 轮次用完", lpos=(CX + 16, (T("judge", "B")[1] + T("report", "T")[1]) / 2))
arrow([T("report", "B"), T("push", "T")])
arrow([T("push", "B"), T("save", "T")])

for k in ("ask", "req", "ctx", "clarify", "pause", "topic", "plan", "exec", "judge", "report", "push", "save"):
    box(NODES[k])

# ============================ 图例 ============================
lyy = H - 54
d.text((S(96), S(lyy)), "图例：", font=font(13.5, True), fill=GRAY, anchor="lm")
xx = 156
for txt, col in (("澄清 / 规划", (224, 123, 26)), ("检索 / 落库", (61, 154, 80)),
                 ("模型生成", (74, 126, 187)), ("中断分支", (135, 148, 165))):
    d.rounded_rectangle([S(xx), S(lyy - 9), S(xx + 16), S(lyy + 7)], radius=S(4), fill=(255, 255, 255),
                        outline=col, width=max(1, int(round(1.8 * SCALE))))
    d.text((S(xx + 24), S(lyy)), txt, font=font(13.5), fill=(70, 88, 106), anchor="lm")
    xx += 30 + int(meas(txt, 13.5)) + 26
d.text((S(W / 2), S(H - 24)), "虚线框内为可重复执行的循环过程；未通过评估则带着反馈重新规划，最多 3 轮",
       font=font(13), fill=GRAY, anchor="mm")

here = os.path.dirname(os.path.abspath(__file__))
jpg = os.path.join(here, "deep-research-flow.jpg")
img.save(jpg, quality=93, subsampling=0, optimize=True, progressive=True)
print("saved %s  %dx%d  %.2f MB" % (jpg, img.width, img.height, os.path.getsize(jpg) / 1e6))
