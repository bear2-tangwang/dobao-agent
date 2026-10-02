# PPT 自由创作路径 · 执行方案(v2 · python-pptx 版)

> 目标:在**不推翻现有模板填充架构**的前提下,新增一条「布局原语 + 设计 Token + 受约束随机化」的自由创作路径。
> 技术选型:**渲染层用 python-pptx,布局原语放 Python**;Java 侧只负责编排与调度。
> 版本:v2.0 ｜ 适用范围:`dodo-agent` 模块 ｜ 关联包:`cn.hollis.llm.mentor.agent`

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [设计原则](#2-设计原则)
3. [技术选型(为什么是 python-pptx)](#3-技术选型为什么是-python-pptx)
4. [总体架构](#4-总体架构)
5. [详细设计](#5-详细设计)
   - [5.1 生成模式与状态机改造(Java)](#51-生成模式与状态机改造java)
   - [5.2 设计 Token(theme-pool.json)](#52-设计-tokentheme-pooljson)
   - [5.3 布局原语规范(Python)](#53-布局原语规范python)
   - [5.4 LLM 输出契约(FreeDeckSpec)](#54-llm-输出契约freedeckspec)
   - [5.5 布局引擎(Python)](#55-布局引擎python)
   - [5.6 随机化引擎(Python)](#56-随机化引擎python)
   - [5.7 渲染层(python-pptx)](#57-渲染层python-pptx)
   - [5.8 进程调用与数据交换](#58-进程调用与数据交换)
   - [5.9 配图能力(Phase 4)](#59-配图能力phase-4)
   - [5.10 修改流程](#510-修改流程)
6. [代码清单](#6-代码清单)
7. [配置与数据库变更](#7-配置与数据库变更)
8. [分阶段实施计划](#8-分阶段实施计划)
9. [测试与验收](#9-测试与验收)
10. [风险与回退](#10-风险与回退)
- [附录 A:FreeDeckSpec 完整示例](#附录-afreedeckspec-完整示例)
- [附录 B:CARDS 原语 Python 参考实现](#附录-bcards-原语-python-参考实现)
- [附录 C:文字容量估算与降级规则](#附录-c文字容量估算与降级规则)
- [附录 D:决策记录(为什么不用 Java POI)](#附录-d决策记录为什么不用-java-poi)

---

## 1. 背景与目标

### 1.1 现状

`dodo-agent` 当前只有一条 PPT 生成路径——**模板填充**:

```
REQUIREMENT → SEARCH → TEMPLATE → OUTLINE → SCHEMA → RENDER → SUCCESS
```

对应实现在 `agent/pptx/strategy/`,最终由 `RenderStrategy` 调
`PptPythonRenderServiceImpl.renderPpt()` → `python render_ppt.py`,
在模板 `.pptx` 副本上按 `shape.name` 填充字段,产出可下载的 `.pptx`。

**问题**:模板库是硬成本。每加一种风格要设计整套 `.pptx` + 写 `template_schema`
+ 入 `ai_ppt_template` 表,且产出风格高度同质。

### 1.2 目标

| 项 | 要求 |
|---|---|
| 新增能力 | 一条无需模板库、风格可随机变化的自由创作路径 |
| 兼容性 | **现有模板路径完全不受影响**,任何时刻可切回 |
| 可开关 | 配置一键启用/关闭,关闭后行为与现状完全一致 |
| 产出物 | 真·可编辑 `.pptx`(**禁止整页截图/整页生图**) |
| 质量下限 | 不溢出、不重叠、对比度达标、字号层级一致 |
| 随机性 | 每次生成配色/装饰不同,骨架稳定 |
| 渲染栈 | **与现有渲染栈保持一致**(python-pptx),不引入第二套 |

### 1.3 非目标

- 不做在线可视化编辑器(独立需求,参考 lingclaw 的 `SlideCanvas`)
- 不做「LLM 直接输出绝对坐标」(质量下限不可控)
- 不动 `render_ppt.py` 与现有模板链路

---

## 2. 设计原则

### 2.1 核心原则:随机化「皮肤」,固化「骨架」

| 层次 | 内容 | 是否随机 | 理由 |
|---|---|---|---|
| **皮肤层** | 配色、装饰形状、强调色落点、圆角大小 | ✅ 随机 | 越随机越新鲜,几乎不会崩 |
| **骨架层** | 信息层级、对齐、安全边距、字号层级、不重叠 | ❌ 固化 | 一随机就出事故 |
| **内容层** | 标题、要点、配图提示词 | ✅ LLM 决定 | LLM 的强项 |

**这是整个方案的地基。**绝大多数「AI 做 PPT 很丑」的案例,都是把随机性加到了骨架层。

### 2.2 次级原则

1. **LLM 不碰坐标**。只输出「用哪个布局 + 放什么内容」,坐标由 Python 引擎计算。
2. **永不截断文字**。容量不足时按「缩字号 → 换布局 → 拆页」三级降级,绝不加省略号。
   (现有 `render_ppt.py:78` 的 `text[:font_limit]` 是硬截断,自由路径**不沿用**;
   模板路径保持不动以免影响存量效果。)
3. **容量必须可计算**。盒子尺寸来自 Token 与网格公式,不靠 LLM 估算。
4. **降级而非失败**。非法 `layout` 兜底 `BULLETS`;内容超量自动降级。
5. **可重现**。给定 seed,同一份 Spec 必须产出像素级一致的 pptx。
6. **单栈渲染**。所有 PPT 渲染最终都走 python-pptx,团队只维护一套心智模型。

---

## 3. 技术选型(为什么是 python-pptx)

### 3.1 结论

**渲染层用 python-pptx,布局原语也放 Python。** 不用 Java POI。

### 3.2 理由

| 维度 | Java POI | python-pptx | 影响 |
|---|---|---|---|
| **原生图表** | ❌ 实际不可用。`XSLFChart` 编程式创建极复杂,lingclaw 因此直接放弃(注释原话:"POI原生图表API过于复杂,采用占位方案"),导出后只剩灰底文本框写 `[图表: bar]` | ✅ `add_chart()` 写原生 OOXML 图表,支持柱/折线/饼/散点,可 `replace_data()` 更新 | **决定性** |
| **代码量** | 多。手动管 `XSLFTextParagraph`/`XSLFTextRun`;圆角要改 `avLst` 的 adj | 少。`run.font.size = Pt(18)`;圆角用 `MSO_SHAPE.ROUNDED_RECTANGLE` | 中 |
| **自动适配** | 需自行实现 | 有 `MSO_AUTO_SIZE` / `word_wrap` / `vertical_anchor` | 中 |
| **渲染栈数量** | 2 套(模板走 Python、自由走 Java) | **1 套** | 高 |
| **进程开销** | 无 | 有子进程,**但基建已就绪**(超时/临时文件/输出目录/MinIO 上传都已实现) | 低 |
| **"零新增依赖"** | ✅ 表面成立 | — | **不成立,见下** |

### 3.3 关键澄清:python-pptx 不是"新增"依赖

`PptPythonRenderServiceImpl` 是当前**唯一**的渲染入口,它 `ProcessBuilder` 起
`python render_ppt.py`。也就是说:

> **生产环境如果没装 python-pptx,现在的 PPT 功能是完全不可用的。**

所以"POI 零新增依赖"这个优势在本项目中不存在——两者都是既有依赖。在同等前提下,
python-pptx 在图表能力和代码量上全面胜出,且能保持单一渲染栈。

### 3.4 附带收益:Java 侧代码量大幅下降

布局原语放 Python 后,Java↔Python 之间只传**很小的 Spec JSON**(约 3KB),
而不是把算好的几百个元素坐标序列化过去。Java 新增文件从原 POI 方案的 28 个降到 **8 个**。

### 3.5 代价(需接受)

| 代价 | 缓解 |
|---|---|
| 布局逻辑在 Python,Java 团队排查成本略高 | 几何逻辑集中在一个包,配 pytest 单测;Python 侧输出结构化日志 |
| Java 无法直接单测布局 | 用 `--dump-elements` 输出元素 JSON,Java 侧做集成断言 |
| 依赖 Python 运行时 | 已有依赖,非新增 |

---

## 4. 总体架构

### 4.1 路径分叉

```
                              ┌──────────────────────────────┐
                              │  REQUIREMENT (需求澄清)       │
                              └──────────────┬───────────────┘
                                             │ 解析 deckMode(新增)
                              ┌──────────────▼───────────────┐
                              │  SEARCH (信息收集)            │
                              └──────────────┬───────────────┘
                                             │
                              ┌──────────────▼───────────────┐
                              │  TEMPLATE (模式分流点) ★      │
                              └───────┬──────────────┬───────┘
                        TEMPLATE 模式 │              │ FREE 模式
                              ┌───────▼──────┐  ┌────▼─────────────┐
                              │  选择模板     │  │ 跳过模板选择       │
                              └───────┬──────┘  └────┬─────────────┘
                                      └──────┬───────┘
                              ┌──────────────▼───────────────┐
                              │  OUTLINE (大纲,双路共用)      │
                              └───────┬──────────────┬───────┘
                        TEMPLATE 模式 │              │ FREE 模式
                              ┌───────▼──────┐  ┌────▼─────────────┐
                              │   SCHEMA     │  │  FREE_SCHEMA ★   │
                              │ (模板字段)    │  │  (布局 Spec)      │
                              └───────┬──────┘  └────┬─────────────┘
                              ┌───────▼──────┐  ┌────▼─────────────┐
                              │   RENDER     │  │  FREE_RENDER ★   │
                              │ render_ppt.py│  │render_free_deck.py│
                              └───────┬──────┘  └────┬─────────────┘
                                      └──────┬───────┘
                              ┌──────────────▼───────────────┐
                              │  SUCCESS                     │
                              └──────────────────────────────┘
```

★ = 本次新增/改造点。

### 4.2 Java / Python 职责边界

```
┌─────────────────────────── Java(编排层) ───────────────────────────┐
│  DeckModeResolver      模式判定                                    │
│  FreeSchemaStrategy    LLM → FreeDeckSpec(只编排,不碰几何)        │
│  FreeRenderStrategy    起 python 子进程 → 拿到 pptx → 传 MinIO      │
│  PptFreeRenderService  进程管理 / 超时 / 清理 / 进度转发            │
└───────────────────────────────┬────────────────────────────────────┘
                                │  只传 3 样东西:
                                │  ① spec JSON(约 3KB)
                                │  ② seed(长整型)
                                │  ③ 输出路径
                                ▼
┌─────────────────────────── Python(渲染层) ──────────────────────────┐
│  theme_pool.py         加载 theme-pool.json                        │
│  theme_sampler.py      seed → 采样配色 + 对比度校验                 │
│  primitives/*.py       布局原语(坐标计算)                          │
│  layout_engine.py      注册表 + 降级链 + 编译                       │
│  textfit.py            容量估算(永不截断)                          │
│  validator.py          几何自检(越界/重叠/容量)                    │
│  renderer.py           python-pptx 绘制 → .pptx                    │
└────────────────────────────────────────────────────────────────────┘
```

**关键点**:LLM 与最终渲染之间隔着一整个 Python 布局引擎。LLM 想让版面崩掉,结构上做不到。

---

## 5. 详细设计

### 5.1 生成模式与状态机改造(Java)

#### 5.1.1 新增状态枚举

`entity/record/pptx/PptInstStatus.java` 追加两个值(`status` 列为 varchar,**无需 DDL**):

```java
/**
 * 自由创作 - 布局Spec生成
 */
FREE_SCHEMA("FREE_SCHEMA", "自由布局生成"),
/**
 * 自由创作 - 渲染
 */
FREE_RENDER("FREE_RENDER", "自由创作渲染"),
```

#### 5.1.2 新增模式标识

`AiPptInst` 追加两个字段(需 DDL,见 [第 7 章](#7-配置与数据库变更)):

```java
/**
 * 生成模式:TEMPLATE(模板填充)/ FREE(自由创作)
 */
@TableField("deck_mode")
private String deckMode;

/**
 * 主题随机种子(FREE 模式下保证可重现)
 */
@TableField("theme_seed")
private Long themeSeed;
```

`DeckMode` 枚举:

```java
public enum DeckMode {
    TEMPLATE, FREE;

    /** 默认 TEMPLATE,保证历史数据与兜底行为一致 */
    public static DeckMode fromCode(String code) {
        return FREE.name().equalsIgnoreCase(code) ? FREE : TEMPLATE;
    }
}
```

#### 5.1.3 策略工厂注册

`PptStateStrategyFactory` 静态块追加:

```java
STRATEGY_MAP.put(PptInstStatus.FREE_SCHEMA, new FreeSchemaStrategy());
STRATEGY_MAP.put(PptInstStatus.FREE_RENDER, new FreeRenderStrategy());
```

并追加修改流程入口:

```java
/**
 * 自由创作的修改流程入口
 */
public void executeFreeSchemaStrategy(AiPptInst inst, Sinks.Many<String> sink, String query,
                                      StringBuilder thinkingBuffer, PptStateStrategyContext context) {
    FreeSchemaStrategy strategy = new FreeSchemaStrategy();
    String prompt = PptBuilderPrompts.getFreeDeckModifyPrompt(query, inst.getPptSchema());
    strategy.executeWithModifyPrompt(inst, sink, query, thinkingBuffer, context, prompt);
}
```

#### 5.1.4 分流点一:`TemplateStrategy`

在方法开头插入模式判断,**FREE 模式直接跳过模板选择**(不调 LLM、不改 `templateCode`):

```java
@Override
public void execute(AiPptInst inst, Sinks.Many<String> sink, String query,
                    StringBuilder thinkingBuffer, PptStateStrategyContext context) {

    // ★ 新增:自由创作模式跳过模板选择
    if (DeckMode.fromCode(inst.getDeckMode()) == DeckMode.FREE) {
        sink.tryEmitNext(context.createThinkingResponse("🎨 自由创作模式,跳过模板选择\n"));
        context.getPptInstService().updateStatus(inst.getId(), PptInstStatus.OUTLINE);
        context.continueStateMachine(inst, sink, query, thinkingBuffer);
        return;
    }

    // ... 以下为原有模板选择逻辑,完全不动
}
```

> `AiPptInstService` 需补一个通用 `updateStatus(Long id, PptInstStatus status)`。

#### 5.1.5 分流点二:`OutlineStrategy`

`TARGET_STATUS` 目前是常量 `SCHEMA`,需改为**按模式动态决定**:

```java
@Override
public void execute(AiPptInst inst, Sinks.Many<String> sink, String query,
                    StringBuilder thinkingBuffer, PptStateStrategyContext context) {
    DeckMode mode = DeckMode.fromCode(inst.getDeckMode());

    // 目标状态动态化
    PptInstStatus target = (mode == DeckMode.FREE)
            ? PptInstStatus.FREE_SCHEMA
            : PptInstStatus.SCHEMA;

    // 提示词按模式选择
    String prompt;
    if (mode == DeckMode.FREE) {
        // 不传 templateSchema,改传「布局原语目录」,
        // 引导 LLM 把每页规划成「语义单元」而不是「模板字段」
        prompt = PptBuilderPrompts.getFreeOutlinePrompt(
                inst.getRequirement(), inst.getSearchInfo(), LayoutCatalogText.describe());
    } else {
        AiPptTemplate template = context.getPptTemplateService().getByCode(inst.getTemplateCode());
        prompt = PptBuilderPrompts.getOutlinePrompt(
                inst.getRequirement(), template.getTemplateSchema(),
                template.getTemplateName(), inst.getSearchInfo());
    }

    // ... 原有流式调用逻辑不变,仅把落库时的 target 换成变量
    context.getPptInstService().updateOutline(inst.getId(), outlineContent.toString(), target);
}
```

> ⚠️ `getTargetStatus()` 是接口方法,无法返回动态值。**保留其返回 `SCHEMA` 不变**
> (仅用于日志/兜底),实际流转以 `updateOutline(...)` 传入的 `target` 为准。
> 请在方法注释里写明,避免后人误改。

#### 5.1.6 模式如何决定

```java
public final class DeckModeResolver {

    private static final String[] FORCE_TEMPLATE = {
            "用我们公司", "按公司模板", "指定模板", "用这个模板", "套模板", "沿用模板"
    };
    private static final String[] FORCE_FREE = {
            "自由发挥", "随便设计", "原创", "不要模板", "自己设计", "有创意", "自由创作"
    };

    /**
     * 解析生成模式。优先级:用户显式指定 > 配置默认值
     */
    public static DeckMode resolve(String query, String requirement, PptFreeProperties props) {
        String text = (query == null ? "" : query) + "\n" + (requirement == null ? "" : requirement);

        // 1) 显式要求公司/指定模板 → TEMPLATE(不可被配置覆盖)
        if (containsAny(text, FORCE_TEMPLATE)) return DeckMode.TEMPLATE;
        // 2) 显式要求自由发挥 → FREE
        if (containsAny(text, FORCE_FREE)) return DeckMode.FREE;
        // 3) 配置默认值
        return props.getDefaultMode();
    }
}
```

`ppt.free.default-mode` 取值:

| 值 | 行为 |
|---|---|
| `template` | 永远走模板(等价于关闭新路径,**默认值,最稳**) |
| `free` | 永远走自由创作 |
| `auto` | 用户没明说时走 FREE;说了「公司模板」走 TEMPLATE |

**灰度建议**:上线先设 `template`(功能关闭),验证环境设 `free`,稳定后设 `auto`。

#### 5.1.7 修改流程分支

`PPTBuilderAgent.handleModifyIntent` 目前无条件调 `executeSchemaStrategy`,需分流:

```java
DeckMode mode = DeckMode.fromCode(inst.getDeckMode());
strategyContext.setModifyMode(true);
strategyContext.setModifyQuery(query);

if (mode == DeckMode.FREE) {
    PptStateStrategyFactory.getInstance()
            .executeFreeSchemaStrategy(inst, sink, query, thinkingBuffer, strategyContext);
} else {
    executeModifyFlow(inst, query, sink, thinkingBuffer);   // 原有逻辑
}
```

---

### 5.2 设计 Token(theme-pool.json)

**这是唯一的"配置型资产",里面没有任何布局信息。**

路径:`src/main/resources/python/freeppt/theme-pool.json`

> 放在 `freeppt/` 包内是为了让 Python 用 `Path(__file__).parent / "theme-pool.json"`
> 自包含加载,**不依赖运行时工作目录**,也不用 Java 传路径。

```json
{
  "version": 1,
  "canvas": {
    "widthCm": 33.87,
    "heightCm": 19.05,
    "aspect": "16:9"
  },
  "safeArea": {
    "marginXCm": 2.2,
    "marginYCm": 1.5
  },
  "typeScale": {
    "coverTitle":    44,
    "sectionTitle":  34,
    "pageTitle":     28,
    "cardTitle":     18,
    "body":          16,
    "caption":       12,
    "minBody":       13
  },
  "spacing": {
    "gapCm":            0.8,
    "cardPaddingCm":    0.5,
    "lineHeightFactor": 1.4,
    "cardRadiusCm":     0.2
  },
  "font": {
    "cn": "微软雅黑",
    "en": "Arial"
  },
  "palettes": [
    {
      "id": "deep-blue",
      "name": "商务深蓝",
      "mood": ["商务", "稳重", "科技", "汇报"],
      "primary":   "#1F3A93",
      "accent":    "#C0392B",
      "bg":        "#FFFFFF",
      "surface":   "#F5F7FA",
      "border":    "#E3E7ED",
      "title":     "#1A1A1A",
      "body":      "#4A4A4A",
      "muted":     "#8C8C8C",
      "onPrimary": "#FFFFFF"
    },
    {
      "id": "ink-green",
      "name": "学术墨绿",
      "mood": ["学术", "严谨", "论文", "开题"],
      "primary":   "#2D6A4F",
      "accent":    "#E9C46A",
      "bg":        "#FFFFFF",
      "surface":   "#F4F8F5",
      "border":    "#DDE7E0",
      "title":     "#14261C",
      "body":      "#41544A",
      "muted":     "#8A9A91",
      "onPrimary": "#FFFFFF"
    },
    {
      "id": "warm-orange",
      "name": "活力暖橙",
      "mood": ["活泼", "教学", "培训", "年轻"],
      "primary":   "#E07A2F",
      "accent":    "#2F6FE0",
      "bg":        "#FFFDF9",
      "surface":   "#FDF3E7",
      "border":    "#F0DFC8",
      "title":     "#2B1B0E",
      "body":      "#5A4632",
      "muted":     "#A08A73",
      "onPrimary": "#FFFFFF"
    },
    {
      "id": "party-red",
      "name": "党政正红",
      "mood": ["党政", "党课", "爱国", "庄重"],
      "primary":   "#A8181C",
      "accent":    "#C9A227",
      "bg":        "#FFFFFF",
      "surface":   "#FBF3F3",
      "border":    "#EDD8D8",
      "title":     "#1E0E0E",
      "body":      "#4A3434",
      "muted":     "#967C7C",
      "onPrimary": "#FFFFFF"
    },
    {
      "id": "graphite",
      "name": "极简石墨",
      "mood": ["极简", "高级", "设计", "克制"],
      "primary":   "#2B2B2B",
      "accent":    "#C0A062",
      "bg":        "#FFFFFF",
      "surface":   "#F7F7F7",
      "border":    "#E5E5E5",
      "title":     "#111111",
      "body":      "#4D4D4D",
      "muted":     "#9A9A9A",
      "onPrimary": "#FFFFFF"
    }
  ],
  "decorations": {
    "variants": ["CORNER_BLOCK", "LEFT_BAR", "DOT_GRID", "WATERMARK_LETTER", "NONE"],
    "accentUsage": ["TITLE_BAR", "CARD_TOP", "NUMBER_BADGE", "DIVIDER"]
  }
}
```

#### 5.2.1 维护成本对照

| | 模板填充(现状) | 本方案 |
|---|---|---|
| 加一套风格 | 设计整套 `.pptx`(2~3 天)+ 写 `template_schema` + 入 `ai_ppt_template` 表 | **加 15 行 JSON(30 秒)** |
| 加一种页型 | 重新设计并替换整个模板 | 新增 1 个原语(约半天) |
| 是否需要设计工具 | 需要(PowerPoint/WPS) | **不需要,文本编辑器即可** |

#### 5.2.2 配色对比度校验

`theme_sampler.py` 采样后必须自检:

```python
def _luminance(hex_color: str) -> float:
    """WCAG 2.1 相对亮度"""
    r, g, b = _to_rgb(hex_color)
    def f(c):
        c = c / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)

def contrast_ratio(fg: str, bg: str) -> float:
    l1, l2 = _luminance(fg), _luminance(bg)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)

# 正文要求 >= 4.5,大标题要求 >= 3.0;不达标则按亮度方向压暗/提亮直到通过
```

---

### 5.3 布局原语规范(Python)

布局原语 = 一段**纯计算**函数:输入「语义内容 + Token」,输出「元素列表」。
不碰 LLM、不碰 python-pptx。

#### 5.3.1 原语清单

| LayoutId | 用途 | 内容输入 | 条数区间 | 阶段 |
|---|---|---|---|---|
| `COVER` | 封面 | title, subtitle | — | **Phase 1** |
| `AGENDA` | 目录 | items[] | 3~6 | **Phase 1** |
| `BULLETS` | 要点页 | title, items[] | 3~6 | **Phase 1** |
| `CARDS` | 卡片网格 | title, items[] | 2~6 | **Phase 1** |
| `CLOSING` | 结束页 | title, subtitle | — | **Phase 1** |
| `SECTION` | 章节扉页 | title, subtitle | — | Phase 2 |
| `TWO_COL` | 双栏对照 | title, items[] | 2 | Phase 2 |
| `TIMELINE` | 时间轴/流程 | title, items[] | 3~5 | Phase 2 |
| `STAT` | 数据强调 | title, items[] | 2~4 | Phase 2 |
| `QUOTE` | 金句页 | content, subtitle | — | Phase 2 |
| `IMAGE_TEXT` | 图文混排 | title, content, imagePrompt | — | Phase 4 |

**Phase 1 只做前 5 个**,已能覆盖「封面 → 目录 → 内容 → 结尾」的完整最小闭环。

#### 5.3.2 Base 类

```python
# freeppt/primitives/base.py
from abc import ABC, abstractmethod
from typing import List
from ..elements import Element, RectEl, TextEl


class LayoutNotFit(Exception):
    """当前原语放不下,交由注册表降级"""


class LayoutPrimitive(ABC):
    layout_id: str = ""
    min_items: int = 0
    max_items: int = 999

    @abstractmethod
    def layout(self, slide: dict, theme, ctx) -> List[Element]:
        """返回该页元素列表,坐标单位 cm,原点左上角"""
        raise NotImplementedError

    # ── 供子类复用的公共构件 ──────────────────────────
    def _page_header(self, title: str, theme, ctx) -> List[Element]:
        """通用页头:左侧竖条 + 标题"""
        els: List[Element] = []
        title_h = ctx.pt_to_cm(ctx.type_scale.pageTitle) * ctx.line_height_factor
        els.append(RectEl(x=ctx.safe_x, y=ctx.safe_y + 0.15,
                          w=0.12, h=title_h * 0.7,
                          fill=theme.primary, line=None, radius=0.0, opacity=1.0))
        els.append(TextEl(x=ctx.safe_x + 0.4, y=ctx.safe_y,
                          w=ctx.content_w - 0.4, h=title_h,
                          text=title or "",
                          font_pt=ctx.type_scale.pageTitle, bold=True,
                          color=theme.title, align="LEFT", wrap=False))
        return els
```

#### 5.3.3 元素模型

```python
# freeppt/elements.py
from dataclasses import dataclass
from typing import Optional, Union


@dataclass
class RectEl:
    x: float; y: float; w: float; h: float          # 单位 cm
    fill: Optional[str] = None                       # hex,None = 无填充
    line: Optional[str] = None                       # hex,None = 无边框
    radius: float = 0.0                              # cm
    opacity: float = 1.0


@dataclass
class TextEl:
    x: float; y: float; w: float; h: float
    text: str
    font_pt: int
    bold: bool = False
    color: str = "#000000"
    align: str = "LEFT"                              # LEFT / CENTER / RIGHT
    wrap: bool = True


@dataclass
class LineEl:
    x: float; y: float; w: float; h: float
    color: str = "#CCCCCC"
    width_pt: float = 1.0


@dataclass
class ImageEl:
    x: float; y: float; w: float; h: float
    path_or_url: str = ""                            # Phase 4 启用


Element = Union[RectEl, TextEl, LineEl, ImageEl]
```

#### 5.3.4 分层与 z-order(硬约束)

元素列表顺序 = 绘制顺序(后画在上)。所有原语**必须**按此顺序产出:

```
1. 页面背景(由 renderer 统一处理,原语不产出)
2. 装饰元素(色块、线条、水印)
3. 卡片底板(RectEl)
4. 图片(ImageEl)
5. 文字(TextEl)
```

---

### 5.4 LLM 输出契约(FreeDeckSpec)

#### 5.4.1 数据结构

Java 侧(嵌套 record,单文件):

```java
public class FreeDeckSpec {
    private String title;
    private String subtitle;
    private String paletteHint;     // 可选:颜色/风格词,如「深蓝」「党政红」
    private List<Slide> slides;

    public record Slide(
            String layout,          // LayoutId 名称,如 "CARDS"
            String title,
            String subtitle,
            List<Item> items,
            String imagePrompt,     // 仅 IMAGE_TEXT / COVER 使用
            String notes
    ) {}

    public record Item(String title, String content, String value) {}
}
```

#### 5.4.2 提示词要点(`getFreeDeckSchemaPrompt`)

必须写死的约束:

```
## 输出格式
只输出 JSON,对应 FreeDeckSpec 结构。

## 铁律
1. 严禁输出任何坐标、宽高、字号、颜色值 —— 排版由渲染引擎负责。
   你只决定「用哪个 layout」和「每一页放什么内容」。
2. layout 只能从下面的枚举中选。
3. 严禁在标题或正文结尾使用省略号(... / …)凑长度。
   内容写不下时,请自行用更精炼的措辞重写,或拆成两页。
4. 同一层级的标题用词长度尽量接近,保持版面齐整。
5. items 条数必须落在该 layout 允许的区间内。
6. 不要连续使用同一个 layout 超过 2 页。
7. 每页都要有明确的信息角色:封面 / 目录 / 章节 / 论点 / 论据 / 数据 / 总结 / 结尾。
```

#### 5.4.3 布局目录注入

```java
public final class LayoutCatalogText {
    public static String describe() {
        return """
            - COVER        封面。1 页。字段:title, subtitle。不填 items。
            - AGENDA       目录。1 页。items 3~6 条,每条 title。
            - BULLETS      要点页。items 3~6 条,每条 title(可选) + content。
            - CARDS        卡片网格。items 2~6 条,每条 title + content。
            - CLOSING      结束页。字段:title, subtitle。
            """;
        // Phase 2 追加 SECTION / TWO_COL / TIMELINE / STAT / QUOTE
    }
}
```

> ⚠️ 这份说明必须与 `primitives/` 的注册表**保持同步**。建议在 Python 侧加一个
> `--dump-catalog` 参数输出真实目录,Java 侧启动时校验一致性,避免提示词与实现漂移。

---

### 5.5 布局引擎(Python)

#### 5.5.1 画布上下文

```python
# freeppt/layout_engine.py
from dataclasses import dataclass

CM_PER_PT = 0.03528


@dataclass
class TypeScale:
    coverTitle: int
    sectionTitle: int
    pageTitle: int
    cardTitle: int
    body: int
    caption: int
    minBody: int


@dataclass
class LayoutContext:
    canvas_w: float
    canvas_h: float
    safe_x: float
    safe_y: float
    content_w: float
    content_h: float
    gap: float
    card_padding: float
    card_radius: float
    line_height_factor: float
    type_scale: TypeScale

    @staticmethod
    def from_pool(pool: dict) -> "LayoutContext":
        c, s, t = pool["canvas"], pool["safeArea"], pool["spacing"]
        return LayoutContext(
            canvas_w=c["widthCm"], canvas_h=c["heightCm"],
            safe_x=s["marginXCm"], safe_y=s["marginYCm"],
            content_w=c["widthCm"] - 2 * s["marginXCm"],
            content_h=c["heightCm"] - 2 * s["marginYCm"],
            gap=t["gapCm"], card_padding=t["cardPaddingCm"],
            card_radius=t["cardRadiusCm"],
            line_height_factor=t["lineHeightFactor"],
            type_scale=TypeScale(**pool["typeScale"]),
        )

    def pt_to_cm(self, pt: float) -> float:
        return pt * CM_PER_PT
```

#### 5.5.2 注册表与降级链

```python
# freeppt/layout_engine.py(续)
import logging
from .primitives.cover import CoverPrimitive
from .primitives.agenda import AgendaPrimitive
from .primitives.bullets import BulletsPrimitive
from .primitives.cards import CardsPrimitive
from .primitives.closing import ClosingPrimitive

log = logging.getLogger("freeppt")

_REGISTRY = {}
for _p in (CoverPrimitive(), AgendaPrimitive(), BulletsPrimitive(),
           CardsPrimitive(), ClosingPrimitive()):
    _REGISTRY[_p.layout_id] = _p

# 降级链:越靠前越理想
_FALLBACK_CHAIN = {
    "CARDS":    ["CARDS", "BULLETS"],
    "TIMELINE": ["TIMELINE", "BULLETS"],
    "TWO_COL":  ["TWO_COL", "BULLETS"],
    "STAT":     ["STAT", "CARDS", "BULLETS"],
    "AGENDA":   ["AGENDA", "BULLETS"],
}


def resolve(slide: dict):
    """按名称解析原语;未知名或条数越界时沿降级链回退,最终兜底 BULLETS"""
    name = (slide.get("layout") or "").strip().upper()
    n = len(slide.get("items") or [])

    chain = _FALLBACK_CHAIN.get(name, [name, "BULLETS"])
    for candidate in chain:
        p = _REGISTRY.get(candidate)
        if p is None:
            continue
        if p.min_items <= n <= p.max_items:
            if candidate != name:
                log.warning("layout=%s 条数=%d 不适配,降级为 %s", name, n, candidate)
            return p

    log.warning("layout=%s 无法解析,兜底 BULLETS", name)
    return _REGISTRY["BULLETS"]
```

#### 5.5.3 编译入口

```python
from dataclasses import dataclass
from typing import List

from .theme_sampler import sample_theme
from .primitives.base import LayoutNotFit


@dataclass
class CompiledDeck:
    theme: object
    pages: List[List[object]]
    spec: dict
    canvas_w: float
    canvas_h: float


def compile_deck(spec: dict, seed: int, pool: dict) -> CompiledDeck:
    """把 Spec 编译成「每页元素列表」"""
    theme = sample_theme(pool, seed, spec.get("paletteHint"))
    ctx = LayoutContext.from_pool(pool)

    pages = []
    for slide in spec.get("slides", []):
        primitive = resolve(slide)
        try:
            els = primitive.layout(slide, theme, ctx)
        except LayoutNotFit:
            # 原语自己放不下 → 强制兜底 BULLETS
            log.warning("layout=%s 内容放不下,兜底 BULLETS", primitive.layout_id)
            els = _REGISTRY["BULLETS"].layout(slide, theme, ctx)
        pages.append(els)

    return CompiledDeck(theme=theme, pages=pages, spec=spec,
                        canvas_w=ctx.canvas_w, canvas_h=ctx.canvas_h)
```

#### 5.5.4 容量保护(关键)

```python
# freeppt/textfit.py
import math

CM_PER_PT = 0.03528
CARD_PADDING_CM = 0.5


def visual_width(s: str) -> float:
    """视觉宽度:CJK=1.0,空格=0.35,ASCII=0.5,其他=0.8"""
    w = 0.0
    for ch in s or "":
        o = ord(ch)
        if 0x4E00 <= o <= 0x9FFF or 0x3000 <= o <= 0x303F or 0xFF00 <= o <= 0xFFEF:
            w += 1.0
        elif ch == " ":
            w += 0.35
        elif o < 128:
            w += 0.5
        else:
            w += 0.8
    return w


def chars_per_line(box_w_cm: float, font_pt: int,
                   padding_cm: float = CARD_PADDING_CM) -> int:
    usable = max(0.1, box_w_cm - 2 * padding_cm)
    return max(1, math.floor(usable / (font_pt * CM_PER_PT)))


def needed_height(text: str, box_w_cm: float, font_pt: int,
                  line_height_factor: float,
                  padding_cm: float = CARD_PADDING_CM) -> float:
    cpl = chars_per_line(box_w_cm, font_pt, padding_cm)
    lines = max(1, math.ceil(visual_width(text) / cpl))
    return lines * font_pt * CM_PER_PT * line_height_factor + 2 * padding_cm


def fit_font_size(text: str, box_w_cm: float, box_h_cm: float,
                  preferred_pt: int, min_pt: int,
                  line_height_factor: float) -> int:
    """
    逐档降字号,返回能放下的字号;全部放不下返回 -1。
    ⚠️ 永不截断文字。
    """
    pt = preferred_pt
    while pt >= min_pt:
        if needed_height(text, box_w_cm, pt, line_height_factor) <= box_h_cm:
            return pt
        pt -= 1
    return -1
```

**规则:`fit_font_size` 返回 -1 时,由原语抛 `LayoutNotFit`,交由引擎降级。永不截断。**

---

### 5.6 随机化引擎(Python)

#### 5.6.1 种子来源

```java
// Java 侧:优先用库里存的,保证「修改」时配色不变
long seed = inst.getThemeSeed() != null
        ? inst.getThemeSeed()
        : Math.abs(new Random().nextLong());
```

生成后写回 `ai_ppt_inst.theme_seed`。

#### 5.6.2 采样策略

```python
# freeppt/theme_sampler.py
import random
from .color_util import contrast_ratio, darken, lighten, luminance


def sample_theme(pool: dict, seed: int, palette_hint: str = None):
    rng = random.Random(seed)
    palettes = pool["palettes"]

    # 1) paletteHint 命中 mood / name → 用户意图优先
    chosen = _match_hint(palettes, palette_hint)
    # 2) 否则随机
    if chosen is None:
        chosen = rng.choice(palettes)

    token = ThemeToken.from_dict(chosen)

    # 3) 对比度校验与自动修正
    token.title = _ensure_contrast(token.title, token.bg, 4.5)
    token.body = _ensure_contrast(token.body, token.bg, 4.5)
    token.title = _ensure_contrast(token.title, token.surface, 4.5)
    token.body = _ensure_contrast(token.body, token.surface, 4.5)
    return token


def _ensure_contrast(fg: str, bg: str, min_ratio: float) -> str:
    """不达标就按亮度方向压暗/提亮,直到通过"""
    cur = fg
    for _ in range(20):
        if contrast_ratio(cur, bg) >= min_ratio:
            return cur
        # 背景亮 → 前景压暗;背景暗 → 前景提亮
        cur = darken(cur, 0.08) if luminance(bg) > 0.5 else lighten(cur, 0.08)
    return cur
```

#### 5.6.3 可随机 / 不可随机 清单(实现硬约束)

| 可随机 ✅ | 不可随机 ❌ |
|---|---|
| 配色方案(5 选 1) | 安全边距 |
| 装饰变体(5 选 1) | 字号层级 |
| 强调色落点(4 选 1) | 网格列数与间距公式 |
| 封面标题居左/居中 | 元素不重叠约束 |
| 卡片圆角大小(0~0.3cm) | 文字容量计算 |
| 背景是否用 surface 色 | z-order |
| 装饰色块位置比例 | 降级链顺序 |

---

### 5.7 渲染层(python-pptx)

#### 5.7.1 中文字体(最重要的坑)

现有 `render_ppt.py` **从来不会**踩这个坑——因为它是**在模板上改字**,模板设计师
早已把 `<a:ea>`(东亚字体)设好,改 run 文本时格式继承下来即可。

而自由路径是**从零 `Presentation()`**,没有任何继承。此时:

> `run.font.name = "微软雅黑"` **只写 `<a:latin>`**,中文字符会 fallback 到主题默认
> 东亚字体(可能是宋体,甚至显示方框)。

必须手动补 `<a:ea>`:

```python
# freeppt/renderer.py
from pptx.oxml.ns import qn


def apply_font(run, cn_font: str, en_font: str) -> None:
    """同时设置 latin 与 ea 字体,缺一不可"""
    run.font.name = en_font                      # <a:latin typeface="Arial"/>
    rPr = run.font._rPr                          # 底层 CT_TextCharacterProperties

    # 清理已有的 ea / cs,避免重复累积
    for tag in ("a:ea", "a:cs"):
        for el in rPr.findall(qn(tag)):
            rPr.remove(el)

    # OOXML 要求的顺序是 latin → ea → cs,先设 latin 再 append ea 即满足
    ea = rPr.makeelement(qn("a:ea"), {"typeface": cn_font})
    rPr.append(ea)
```

#### 5.7.2 页面与背景

```python
import io
from pptx import Presentation
from pptx.util import Cm, Pt
from pptx.dml.color import RGBColor


def render_deck(deck: CompiledDeck, pool: dict) -> bytes:
    prs = Presentation()
    prs.slide_width = Cm(deck.canvas_w)
    prs.slide_height = Cm(deck.canvas_h)

    blank_layout = prs.slide_layouts[6]          # 默认模板第 6 个是 Blank

    for page in deck.pages:
        slide = prs.slides.add_slide(blank_layout)
        # 页面背景色
        slide.background.fill.solid()
        slide.background.fill.fore_color.rgb = RGBColor.from_string(
            deck.theme.bg.lstrip("#"))

        for el in page:
            _draw(slide, el, pool)

    buf = io.BytesIO()
    prs.save(buf)
    return buf.getvalue()
```

#### 5.7.3 各元素绘制

```python
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_AUTO_SIZE


def _draw(slide, el, pool):
    if isinstance(el, RectEl):
        _draw_rect(slide, el)
    elif isinstance(el, TextEl):
        _draw_text(slide, el, pool["font"])
    elif isinstance(el, LineEl):
        _draw_line(slide, el)
    elif isinstance(el, ImageEl):
        _draw_image(slide, el)


def _draw_rect(slide, el: RectEl):
    if el.radius and el.radius > 0:
        shape = slide.shapes.add_shape(
            MSO_SHAPE.ROUNDED_RECTANGLE,
            Cm(el.x), Cm(el.y), Cm(el.w), Cm(el.h))
        # adjustments[0] 是圆角占短边的比例(0~0.5)
        short = max(0.01, min(el.w, el.h))
        shape.adjustments[0] = min(0.5, el.radius / short)
    else:
        shape = slide.shapes.add_shape(
            MSO_SHAPE.RECTANGLE, Cm(el.x), Cm(el.y), Cm(el.w), Cm(el.h))

    if el.fill:
        shape.fill.solid()
        shape.fill.fore_color.rgb = RGBColor.from_string(el.fill.lstrip("#"))
    else:
        shape.fill.background()

    if el.line:
        shape.line.color.rgb = RGBColor.from_string(el.line.lstrip("#"))
    else:
        shape.line.fill.background()

    shape.shadow.inherit = False        # 去掉默认阴影,否则版面会显脏


def _draw_text(slide, el: TextEl, font_cfg: dict):
    box = slide.shapes.add_textbox(Cm(el.x), Cm(el.y), Cm(el.w), Cm(el.h))
    tf = box.text_frame
    tf.word_wrap = el.wrap
    tf.auto_size = MSO_AUTO_SIZE.NONE            # 关掉自动缩字,保证导出与预期一致
    # 清掉默认内边距,坐标才可控
    tf.margin_left = tf.margin_right = 0
    tf.margin_top = tf.margin_bottom = 0

    p = tf.paragraphs[0]
    p.alignment = {"LEFT": PP_ALIGN.LEFT,
                   "CENTER": PP_ALIGN.CENTER,
                   "RIGHT": PP_ALIGN.RIGHT}[el.align]

    run = p.add_run()
    run.text = el.text or ""
    run.font.size = Pt(el.font_pt)
    run.font.bold = el.bold
    run.font.color.rgb = RGBColor.from_string(el.color.lstrip("#"))
    apply_font(run, font_cfg["cn"], font_cfg["en"])     # ★ 关键
```

#### 5.7.4 已知限制

| 限制 | 处理 |
|---|---|
| `fill` 不支持透明度 | python-pptx 无直接 API,需改 XML 加 `<a:alpha>`。**Phase 1 不用透明**,用浅色实色替代 |
| `shape.shadow` 默认继承主题 | 必须 `shadow.inherit = False` 显式关掉,否则卡片会带一层灰阴影 |
| `adjustments[0]` 圆角是比例不是绝对值 | 需 `radius / min(w, h)` 换算,注意 `min` 不能为 0 |

#### 5.7.5 进度输出

Python 侧按行输出 JSON 到 stdout,Java 侧逐行读取并转发到 `sink`:

```python
def progress(stage: str, current: int = 0, total: int = 0, msg: str = ""):
    print(json.dumps({"stage": stage, "current": current,
                      "total": total, "msg": msg}, ensure_ascii=False), flush=True)
```

---

### 5.8 进程调用与数据交换

#### 5.8.1 命令行契约

```bash
python <script_dir>/render_free_deck.py \
    --spec       <spec.json 临时文件路径> \
    --seed       <长整型> \
    --out        <输出 pptx 路径> \
    [--theme-pool <可选,默认脚本同目录>] \
    [--dump-elements <可选,只算不画,输出元素 JSON,便于测试>]
```

**为什么用临时文件传 spec 而不是环境变量**:现有 `PptPythonRenderServiceImpl`
用 `PPT_SCHEMA` 环境变量并在 >20KB 时退化到临时文件——那是因为 Windows 环境变量有大小限制。
这里直接用参数传路径最干净,不需要任何 size hack。

#### 5.8.2 Java 侧服务

```java
public interface PptFreeRenderService {
    /**
     * 渲染自由创作 PPT 并上传 MinIO。
     *
     * @param specJson       FreeDeckSpec 的 JSON
     * @param seed           主题随机种子
     * @param conversationId 会话 ID(用于 MinIO 对象路径)
     * @param onProgress     进度回调(转发到 sink),可为 null
     * @return MinIO 文件 URL
     */
    String renderFreeDeck(String specJson, long seed, String conversationId,
                          Consumer<String> onProgress) throws Exception;
}
```

实现要点:

```java
@Override
public String renderFreeDeck(String specJson, long seed, String conversationId,
                             Consumer<String> onProgress) throws Exception {

    Path specFile = Files.createTempFile("free_deck_", ".json");
    Files.writeString(specFile, specJson, StandardCharsets.UTF_8);

    Path outFile = outputDir().resolve("free_" + System.nanoTime() + ".pptx");

    List<String> cmd = List.of(
            props.getBin(),
            pythonScriptPath("render_free_deck.py"),
            "--spec", specFile.toAbsolutePath().toString(),
            "--seed", String.valueOf(seed),
            "--out",  outFile.toAbsolutePath().toString());

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    pb.environment().put("PYTHONIOENCODING", "utf-8");

    Process proc = pb.start();
    try (BufferedReader r = new BufferedReader(
            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
            log.info("[freeppt] {}", line);
            if (onProgress != null) onProgress.accept(line);
        }
    }
    // 超时 + 退出码校验 + 产物存在性校验 ...
    // 上传 MinIO → 删除本地临时文件
}
```

#### 5.8.3 顺手修掉硬编码路径(强烈建议)

现有 `PptPythonRenderServiceImpl.getPythonScriptPath()` 返回:

```java
return "D:\\java-code\\LLMentor\\agent\\dodo-agent\\src\\main\\resources\\python\\render_ppt.py";
```

**这是部署地雷**——换机器或打包成 jar 就失效。建议一并重构:

```java
private String pythonScriptPath(String scriptName) {
    // 1) 配置优先
    String configured = props.getDir();
    if (StringUtils.hasText(configured)) {
        return Paths.get(configured, scriptName).toString();
    }
    // 2) 开发态:源码目录
    Path dev = Paths.get(System.getProperty("user.dir"),
                         "src", "main", "resources", "python", scriptName);
    if (Files.exists(dev)) return dev.toString();
    // 3) 运行态:外部化目录(部署时把 python/ 拷出来)
    return Paths.get(System.getProperty("user.dir"), "python", scriptName).toString();
}
```

配置项:`ppt.python.dir`(留空则走上面的探测链)。
**部署时需把 `src/main/resources/python/` 整个目录外置**,否则打进 jar 无法执行。

---

### 5.9 配图能力(Phase 4)

`SchemaStrategy.processImageGeneration()` 已有完整的「收集 image/background 字段 →
调 `ImageGenerationService` → 下载 → 上传 MinIO → 回填 url」逻辑。
建议抽成公共组件两条路径共用:

```java
@Service
@RequiredArgsConstructor
public class PptImageAssetService {
    /**
     * 为一批图片提示词生成素材并上传 MinIO。
     * @return 每个提示词对应的 MinIO URL(失败项为 null)
     */
    public List<String> generateAndUpload(List<String> prompts, String conversationId,
                                          Consumer<Progress> onProgress) { ... }
}
```

然后:
- `SchemaStrategy` 改为调用它(**行为不变**,只是搬家)
- `FreeSchemaStrategy` 对 `COVER` / `IMAGE_TEXT` 页的 `imagePrompt` 调用它

> **Phase 1~3 自由路径先不生成配图**,用纯色/几何装饰。
> 这样能把「版式正确性」与「配图质量」两个变量分开验证。

---

### 5.10 修改流程

#### 5.10.1 修改提示词要点

```
## 任务
根据用户的修改需求,修改已有的 FreeDeckSpec。

## 修改规则
1. 只改用户要求改的部分,其余原样保留(包括 layout 选择)。
2. 增删页面时保持 layout 序列的语义合理(不能出现两个连续 COVER)。
3. 仍然严禁输出任何坐标/字号/颜色。
4. 严禁用省略号截断文字。
```

#### 5.10.2 配色必须保持

修改时**必须复用 `inst.themeSeed`**,否则用户会觉得「只改了一个字,整个 PPT 变样了」。

---

## 6. 代码清单

### 6.1 Java 侧新增(8 个)

| # | 路径(相对 `src/main/java/cn/hollis/llm/mentor/agent/`) | 说明 |
|---|---|---|
| 1 | `entity/record/pptx/DeckMode.java` | 模式枚举 |
| 2 | `entity/record/pptx/FreeDeckSpec.java` | LLM 输出契约(嵌套 `Slide` / `Item` record) |
| 3 | `pptx/DeckModeResolver.java` | 模式判定 |
| 4 | `pptx/config/PptFreeProperties.java` | 配置绑定 |
| 5 | `pptx/LayoutCatalogText.java` | 注入提示词的布局目录 |
| 6 | `service/PptFreeRenderService.java` | 渲染服务接口 |
| 7 | `service/impl/PptFreeRenderServiceImpl.java` | 进程调用 + MinIO 上传 |
| 8 | `agent/pptx/strategy/FreeSchemaStrategy.java` | LLM → Spec |
| 9 | `agent/pptx/strategy/FreeRenderStrategy.java` | Spec → pptx |

> 注:包名 `cn.hollis.llm.mentor.agent.pptx` 与现有 `agent.agent.pptx` 不冲突
> (前者挂在 `agent` 直属下)。若想更贴近现有结构,可放 `agent.agent.pptx.free.*`,二选一。

### 6.2 Java 侧修改(11 个)

| # | 文件 | 改动 |
|---|---|---|
| 1 | `entity/record/pptx/PptInstStatus.java` | 追加 `FREE_SCHEMA` / `FREE_RENDER` |
| 2 | `entity/record/pptx/AiPptInst.java` | 追加 `deckMode` / `themeSeed` |
| 3 | `service/AiPptInstService.java` + `impl` | 追加 `updateStatus` / `updateDeckMode` / `updateThemeSeed` |
| 4 | `agent/pptx/strategy/PptStateStrategyFactory.java` | 注册 2 个策略 + `executeFreeSchemaStrategy` |
| 5 | `agent/pptx/strategy/PptStateStrategyContext.java` | 追加 `getFreeRenderService()` |
| 6 | `agent/pptx/strategy/RequirementStrategy.java` | 调 `DeckModeResolver`,落库模式 |
| 7 | `agent/pptx/strategy/TemplateStrategy.java` | FREE 模式跳过 |
| 8 | `agent/pptx/strategy/OutlineStrategy.java` | 动态 target + 分支提示词 |
| 9 | `agent/pptx/PPTBuilderAgent.java` | 注入新 Bean;`handleModifyIntent` 分支 |
| 10 | `prompts/PptBuilderPrompts.java` | 追加 3 个提示词方法 |
| 11 | `service/impl/PptPythonRenderServiceImpl.java` | (建议)修掉硬编码脚本路径 |

### 6.3 Python 侧新增

```
src/main/resources/python/
├── render_ppt.py                    (现有,模板路径,不动)
├── render_free_deck.py              ★ 入口:解析参数 → 编译 → 渲染 → 落盘
└── freeppt/
    ├── __init__.py
    ├── theme-pool.json              ★ 唯一的配置型资产
    ├── color_util.py                  hex / luminance / contrast / darken / lighten
    ├── theme_sampler.py               seed → 采样 + 对比度校验
    ├── textfit.py                     容量估算(永不截断)
    ├── elements.py                    元素 dataclass
    ├── layout_engine.py               LayoutContext / 注册表 / 降级链 / compile_deck
    ├── renderer.py                    python-pptx 绘制
    ├── validator.py                   几何自检(越界/重叠/容量)
    └── primitives/
        ├── __init__.py
        ├── base.py                    LayoutPrimitive 基类 + LayoutNotFit
        ├── cover.py                   ★ Phase 1
        ├── agenda.py                  ★ Phase 1
        ├── bullets.py                 ★ Phase 1
        ├── cards.py                   ★ Phase 1
        └── closing.py                 ★ Phase 1
        # Phase 2 追加 section.py / two_col.py / timeline.py / stat.py / quote.py
        # Phase 4 追加 image_text.py
```

### 6.4 新增提示词方法

```java
/** 自由创作 - 大纲 */
public static String getFreeOutlinePrompt(String requirement, String searchInfo, String layoutCatalog)

/** 自由创作 - Spec 生成 */
public static String getFreeDeckSchemaPrompt(String outline, String layoutCatalog, String paletteHint)

/** 自由创作 - Spec 修改 */
public static String getFreeDeckModifyPrompt(String userRequest, String currentSpec)
```

---

## 7. 配置与数据库变更

### 7.1 application.yml

```yaml
ppt:
  # Python 脚本目录(留空则自动探测,见 5.8.3)
  python:
    dir: ""
    bin: python
  free:
    # 总开关:false 时自由路径完全不生效,行为与现状一致
    enabled: false
    # 默认模式:template(默认,最稳) | free | auto
    default-mode: template
    # 单份 PPT 最大页数
    max-slides: 40
    # 是否启用装饰随机化
    random-decoration: true
    # 渲染超时(秒)
    timeout-seconds: 300
```

### 7.2 数据库变更

```sql
ALTER TABLE ai_ppt_inst
    ADD COLUMN deck_mode  VARCHAR(16) NOT NULL DEFAULT 'TEMPLATE'
        COMMENT '生成模式:TEMPLATE-模板填充 / FREE-自由创作',
    ADD COLUMN theme_seed BIGINT NULL
        COMMENT '主题随机种子,FREE 模式下用于复现配色';
```

**向后兼容**:默认 `'TEMPLATE'`,所有历史数据自动落到模板路径,**无需刷数据**。

> `status` 列若为 `VARCHAR`,新增 `FREE_SCHEMA` / `FREE_RENDER` 无需 DDL。
> 若历史设计用了 ENUM 类型,需同步 `ALTER TABLE ... MODIFY COLUMN`。

### 7.3 部署变更

**必须**:把 `src/main/resources/python/` 外置到部署目录,或通过 `ppt.python.dir` 指向。
打进 jar 的脚本无法直接执行。

---

## 8. 分阶段实施计划

### Phase 0:骨架与开关(0.5 天)

- [ ] Java 侧建包结构 + 全部空实现
- [ ] 加 `deck_mode` / `theme_seed` DDL 与实体字段
- [ ] 加 `ppt.free.*` 配置项,`enabled=false` 时所有新代码不被触发
- [ ] 补 `PptInstStatus` 两个枚举值
- [ ] Python 侧建 `freeppt` 包骨架 + `theme-pool.json`(先放 2 套配色)
- **验收**:项目可正常编译启动,现有 PPT 功能回归通过,配置开关无效用

### Phase 1:最小闭环(2~3 天)★ 核心

**Python**
- [ ] `color_util.py` / `theme_pool.py`
- [ ] `theme_sampler.py`:先**固定取第一套配色**(不做随机,降低变量)
- [ ] `textfit.py`
- [ ] `elements.py` / `layout_engine.py`(含注册表与降级链)
- [ ] `primitives/base.py`
- [ ] **5 个原语**:`cover` / `agenda` / `bullets` / `cards` / `closing`
- [ ] `renderer.py`(重点攻克 `<a:ea>` 中文字体)
- [ ] `render_free_deck.py` 入口

**Java**
- [ ] `FreeSchemaStrategy` + `FreeRenderStrategy` + 状态机接通
- [ ] `PptFreeRenderService` + impl
- [ ] `DeckModeResolver`(先只支持配置强制 FREE,不做语义推断)

**验收**:给一句需求,端到端产出一个 8~12 页 `.pptx` 下载链接,
**文字全部可编辑、中文显示正常、无溢出**。

### Phase 2:原语补齐与降级(2 天)

- [ ] Python 追加 `section` / `two_col` / `timeline` / `stat` / `quote`
- [ ] `validator.py` 几何自检(越界/重叠/容量)
- [ ] 降级链完整实现 + 拆页逻辑
- [ ] Java 侧 `LayoutCatalogText` 与 Python 注册表一致性校验(`--dump-catalog`)
- **验收**:跑 30 组不同条数/长度的内容,无溢出、无重叠

### Phase 3:随机化引擎(1~2 天)

- [ ] 配色池扩到 5 套
- [ ] `ThemeSampler` 真随机 + seed 落库
- [ ] 装饰变体(5 种)
- [ ] 对比度校验与自动修正
- [ ] `paletteHint` 命中逻辑
- **验收**:同一需求连跑 10 次配色/装饰各不相同;固定 seed 重跑结果一致

### Phase 4:配图接入(1~2 天)

- [ ] 抽 `PptImageAssetService`(`SchemaStrategy` 改造为调用它,行为不变)
- [ ] `image_text` 原语 + `cover` 背景图
- [ ] 背景图与文字反差校验(复用 `contrast_ratio`)

### Phase 5:修改流程与打磨(1 天)

- [ ] `executeFreeSchemaStrategy` + 修改提示词
- [ ] 修改时复用 `themeSeed`
- [ ] 配置默认值从 `template` 切到 `auto`
- [ ] (建议)重构 `PptPythonRenderServiceImpl` 的硬编码路径

**合计约 8~11 人日。Phase 1(2~3 天)即可看到端到端效果。**

---

## 9. 测试与验收

### 9.1 Python 单元测试(pytest)

| 测试项 | 断言 |
|---|---|
| `CardsPrimitive` 2/3/4/5/6 条 | 所有盒子在安全区内,互不重叠 |
| `textfit.fit_font_size` 超长文本 | 返回 -1,**不截断** |
| `color_util.contrast_ratio` | 已知色对结果符合 WCAG 公式 |
| `sample_theme` 1000 次 | 所有 `title/bg`、`body/bg` ≥ 4.5 |
| `resolve({"layout": "XXX"})` | 未知 layout 兜底 `BULLETS` |
| `resolve` 条数越界 | 沿降级链回退 |
| **可重现性** | 同 seed + 同 spec → 两次渲染**字节完全一致** |

### 9.2 Java 侧集成测试

```java
// 用 --dump-elements 只算不画,Java 断言元素几何
List<Element> els = runPython("--dump-elements", specJson);
assertAllInCanvas(els);
assertNoOverlap(filterText(els));
```

### 9.3 端到端验收清单

- [ ] 现有模板路径**零回归**(跑一轮完整模板 PPT 生成)
- [ ] `ppt.free.enabled=false` 时行为与改造前完全一致
- [ ] 自由路径产出 8~15 页 PPT,WPS / PowerPoint / Keynote 均可打开
- [ ] 所有文字**可编辑**(非图片)
- [ ] **中文正常显示**(不是宋体、不是方框/豆腐块)← 重点验 `<a:ea>`
- [ ] 无文字溢出、无元素重叠
- [ ] 封面/目录/正文/结尾字号层级一致
- [ ] 同一需求连跑 5 次,视觉风格有明显差异(随机性达标)
- [ ] 固定 seed 重跑,产出完全一致(可重现达标)
- [ ] 无 `...` / `…` / `等等` 结尾的截断文案
- [ ] 生成耗时(不含配图)< 15 秒

---

## 10. 风险与回退

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| 1 | LLM 输出非法 `layout` | 中 | `resolve()` 未知值兜底 `BULLETS` |
| 2 | LLM 输出条数超区间 | 中 | 降级链 `_FALLBACK_CHAIN` 自动回退 |
| 3 | 文本超长 | 高 | `textfit` 三级降级,**永不截断** |
| 4 | **中文显示为宋体/方框** | **高** | 必须写 `<a:ea>`,见 5.7.1;自由路径最易翻车处 |
| 5 | 卡片带默认灰阴影,版面显脏 | 中 | `shape.shadow.inherit = False` |
| 6 | 圆角 `adjustments[0]` 换算错误 | 低 | `radius / min(w, h)`,注意除零 |
| 7 | 装饰随机化偶发难看 | 低 | 装饰变体全部为预置安全构图,不做参数化生成 |
| 8 | 加字段影响历史数据 | 低 | `DEFAULT 'TEMPLATE'`,向后兼容 |
| 9 | 与模板路径代码互相污染 | 中 | 自由路径**独立包**,只通过状态机交互;现有策略最小改动 |
| 10 | 提示词布局目录与 Python 注册表漂移 | 中 | Phase 2 加一致性校验(`--dump-catalog`) |
| 11 | 脚本打进 jar 无法执行 | 中 | 部署时外置 `python/` 目录,或配 `ppt.python.dir` |
| 12 | 配色被 LLM 覆盖导致失控 | 低 | LLM 只能给 `paletteHint`,实际取值必须来自 ThemePool 白名单 |

### 回退方案

| 级别 | 操作 | 生效时间 |
|---|---|---|
| L1 | `ppt.free.default-mode: template` | 新请求立即走模板路径,自由路径代码保留 |
| L2 | `ppt.free.enabled: false` | 自由路径完全不参与 |
| L3 | 回滚代码 + `DROP COLUMN` | 数据库回到改造前(两列均为新增,删除无副作用) |

**三级回退都无需数据订正。**

---

## 附录 A:FreeDeckSpec 完整示例

```json
{
  "title": "2026 年度技术复盘",
  "subtitle": "平台架构组 · 张明",
  "paletteHint": "深蓝",
  "slides": [
    {
      "layout": "COVER",
      "title": "2026 年度技术复盘",
      "subtitle": "平台架构组 · 张明 · 2026.12",
      "notes": "开场:先讲结论,再讲过程。"
    },
    {
      "layout": "AGENDA",
      "title": "本次汇报内容",
      "items": [
        { "title": "业务目标达成情况" },
        { "title": "架构演进与取舍" },
        { "title": "稳定性治理成果" },
        { "title": "明年重点方向" }
      ]
    },
    {
      "layout": "BULLETS",
      "title": "三个值得说的判断",
      "items": [
        { "title": "拆分粒度", "content": "以团队边界而非技术边界拆服务,跨团队联调成本下降明显。" },
        { "title": "存储选型", "content": "核心链路保留 MySQL,仅在日志与检索场景引入 ES 与向量库。" },
        { "title": "灰度策略", "content": "从按机器灰度改为按用户维度灰度,问题影响面下降 80%。" }
      ]
    },
    {
      "layout": "CARDS",
      "title": "核心指标全面达标",
      "items": [
        { "title": "服务可用性", "content": "全年 99.98%,同比提升 0.03 个百分点。" },
        { "title": "接口延迟", "content": "核心接口 P99 降至 128ms,较年初下降 41%。" },
        { "title": "算力成本", "content": "单位算力成本下降 37%,超额完成年度目标。" }
      ]
    },
    {
      "layout": "CLOSING",
      "title": "感谢聆听",
      "subtitle": "欢迎批评指正"
    }
  ]
}
```

> 注意:整个 JSON 里**没有一个坐标、字号或颜色值**。这就是「LLM 不碰排版」的具体体现。

---

## 附录 B:CARDS 原语 Python 参考实现

```python
# freeppt/primitives/cards.py
import math
from typing import List

from .base import LayoutPrimitive, LayoutNotFit
from ..elements import Element, RectEl, TextEl
from ..textfit import visual_width, chars_per_line, CM_PER_PT


class CardsPrimitive(LayoutPrimitive):
    layout_id = "CARDS"
    min_items = 2
    max_items = 6

    def layout(self, slide: dict, theme, ctx) -> List[Element]:
        els: List[Element] = []
        items = slide.get("items") or []
        n = len(items)

        # ── 0. 条数 → 网格 ─────────────────────────────
        if n <= 3:
            cols, rows = n, 1
        elif n == 4:
            cols, rows = 2, 2
        else:                       # 5~6
            cols, rows = 3, 2

        # ── 1. 页头 ────────────────────────────────────
        els.extend(self._page_header(slide.get("title", ""), theme, ctx))
        title_h = ctx.pt_to_cm(ctx.type_scale.pageTitle) * ctx.line_height_factor
        y = ctx.safe_y + title_h + ctx.gap

        # ── 2. 网格几何 ────────────────────────────────
        grid_w = ctx.content_w
        grid_h = ctx.canvas_h - ctx.safe_y - y
        card_w = (grid_w - (cols - 1) * ctx.gap) / cols
        card_h = (grid_h - (rows - 1) * ctx.gap) / rows

        # ── 3. 内容自适应:逐档降字号 ────────────────────
        body_pt = ctx.type_scale.body
        while body_pt >= ctx.type_scale.minBody:
            if all(self._fits(it, card_w, card_h, body_pt, ctx) for it in items):
                break
            body_pt -= 1

        # 降到最小字号仍放不下 → 交给引擎降级
        if body_pt < ctx.type_scale.minBody:
            raise LayoutNotFit(self.layout_id)

        # ── 4. 逐卡片产出元素 ──────────────────────────
        for i, it in enumerate(items):
            col, row = i % cols, i // cols
            cx = ctx.safe_x + col * (card_w + ctx.gap)
            cy = y + row * (card_h + ctx.gap)

            # 4.1 卡片底板
            els.append(RectEl(x=cx, y=cy, w=card_w, h=card_h,
                              fill=theme.surface, line=theme.border,
                              radius=ctx.card_radius, opacity=1.0))
            # 4.2 顶部色条(强调色落点)
            els.append(RectEl(x=cx, y=cy, w=card_w, h=0.12,
                              fill=self._pick_accent(theme, i),
                              line=None, radius=0.0, opacity=1.0))

            # 4.3 卡片标题
            pad = ctx.card_padding
            ty = cy + pad
            if it.get("title"):
                els.append(TextEl(x=cx + pad, y=ty, w=card_w - 2 * pad, h=0.9,
                                  text=it["title"],
                                  font_pt=ctx.type_scale.cardTitle, bold=True,
                                  color=theme.primary, align="LEFT", wrap=False))
                ty += 1.0

            # 4.4 卡片正文
            els.append(TextEl(x=cx + pad, y=ty,
                              w=card_w - 2 * pad,
                              h=cy + card_h - pad - ty,
                              text=it.get("content", ""),
                              font_pt=body_pt, bold=False,
                              color=theme.body, align="LEFT", wrap=True))

        return els

    # ── 辅助 ──────────────────────────────────────────
    def _fits(self, item: dict, card_w: float, card_h: float,
              body_pt: int, ctx) -> bool:
        pad = ctx.card_padding
        text_w = card_w - 2 * pad
        avail_h = card_h - 2 * pad - (1.0 if item.get("title") else 0.0)
        if avail_h <= 0:
            return False
        content = item.get("content", "")
        cpl = chars_per_line(text_w, body_pt, pad)
        lines = max(1, math.ceil(visual_width(content) / cpl))
        need = lines * body_pt * CM_PER_PT * ctx.line_height_factor
        return need <= avail_h

    def _pick_accent(self, theme, index: int) -> str:
        """强调色落点(Phase 3 改为按 seed 采样)"""
        return theme.primary if index % 2 == 0 else theme.accent
```

---

## 附录 C:文字容量估算与降级规则

### C.1 视觉宽度口径

| 字符类别 | 权重 |
|---|---|
| CJK 汉字 / 全角标点(`\u4e00-\u9fff`、`\u3000-\u303f`、`\uff00-\uffef`) | 1.0 |
| 半角空格 | 0.35 |
| ASCII 字母数字标点 | 0.5 |
| 其他(emoji 等) | 0.8 |

单位是「相对于字号的倍数」:1 个汉字 ≈ 1 个字号宽度。

> 该口径与 GordenPPTSkill 的 `_visual_width` 保持一致,便于跨方案对齐经验值。

### C.2 换算常数

```python
CM_PER_PT       = 0.03528     # 1pt = 0.03528cm
LINE_HEIGHT     = 1.4         # 中文正文行高系数
CARD_PADDING_CM = 0.5         # 卡片内边距
```

### C.3 计算式

```
每行可容视觉宽度 cpl = floor((box_w_cm - 2*padding) / (font_pt * CM_PER_PT))
所需行数     lines = ceil(visual_width(text) / cpl)
所需高度     need_h = lines * font_pt * CM_PER_PT * LINE_HEIGHT + 2*padding
```

### C.4 三级降级规则(**永不截断**)

| 级别 | 动作 | 触发条件 |
|---|---|---|
| ① 缩字号 | `body 16 → 15 → … → minBody 13` | `need_h > box_h` |
| ② 换布局 | `CARDS(6) → CARDS(4) → BULLETS(2栏) → BULLETS(1栏)` | ① 用尽仍 `need_h > box_h` |
| ③ 拆页 | 把 items 拆成两页同名 layout | ② 用尽仍放不下 |

**明令禁止**:字符串切片截断、结尾补 `...` / `…` / `等等`。

> 现有 `render_ppt.py:78` 的 `text = text[:font_limit]` 是硬截断,自由路径**不沿用**。
> 模板路径保持不动(避免影响存量效果),建议后续单独排期统一到「重写而非截断」。

---

## 附录 D:决策记录(为什么不用 Java POI)

**决策**:自由创作路径的渲染层采用 python-pptx,布局原语亦置于 Python。

**背景**:初版方案(v1)选择 Java POI,主要论据是「`poi-ooxml` 已在 pom 中,零新增依赖」。

**推翻该论据的事实**:
`PptPythonRenderServiceImpl` 是当前唯一渲染入口,通过 `ProcessBuilder` 调用
`python render_ppt.py`。即生产环境**已经硬依赖** Python + python-pptx。
因此「POI 零新增依赖」在本项目中不成立——两者都是既有依赖。

**在同等前提下比较**:

| 维度 | 权重 | Java POI | python-pptx |
|---|---|---|---|
| 原生图表 | 高 | ❌ 实际不可用(lingclaw 已验证并放弃) | ✅ 完整支持 |
| 渲染栈数量 | 高 | 2 套 | 1 套 |
| 代码量 | 中 | 多 | 少 |
| 子进程开销 | 低 | 无 | 有(基建已就绪) |

**结论**:python-pptx 在图表能力与栈一致性上具有决定性优势,子进程开销由现有基建消化。

**接受的代价**:
- 布局逻辑在 Python,Java 团队排查成本略高 → 集中式包结构 + pytest + `--dump-elements` 缓解
- Java 无法直接单测布局 → 改为集成测试断言几何

**未采纳的替代方案**:
- **Java POI + 布局在 Java**:图表能力缺失,且引入第二套渲染栈。
- **Java 算布局 + Python 只画**:跨语言传几百个元素坐标(JSON 体积大),几何逻辑分散两处,收益不明显。
- **布局求解器(方案 C)**:真正零骨架,但工程量大、质量下限更难保证。**保留为后续演进方向**——
  若 Phase 2 完成后仍觉版式单一,可在此基础上替换中间层(内容 Spec 契约与渲染层可复用)。

---

## 附:方案要点速查

| 问题 | 答案 |
|---|---|
| 渲染用什么? | **python-pptx**(与现有渲染栈一致,支持原生图表) |
| 布局逻辑写在哪? | **Python**(`freeppt/primitives/`) |
| Java 侧要写多少? | 8 个新文件 + 改 11 个,只做编排与调度 |
| 维护成本在哪? | 只有 `theme-pool.json`(加一套配色约 15 行 JSON) |
| theme.json 里有布局吗? | **没有**,只有配色/字号/间距。布局是代码,不是 JSON |
| 随机性从哪来? | 配色池采样 + 装饰变体 + 强调色落点 + 内容驱动布局变体 |
| 为什么不会崩? | LLM 不碰坐标;坐标由网格公式算;三级降级兜底 |
| 会影响现有功能吗? | 不会,独立包 + 状态机分流 + 三级开关回退 |
| 最难的坑是什么? | python-pptx 的中文字体必须补 `<a:ea>`(自由路径才会踩,模板路径不会) |
| Phase 1 做多少? | 5 个原语(封面/目录/要点/卡片/结尾),2~3 天看到端到端效果 |
| 总工作量? | 约 8~11 人日 |
