---
summary: "HTML 기반 설계 문서 작성 컨벤션. 디자인 시스템 토큰, 레이아웃, 컴포넌트, 가독성 규칙."
status: stable
read_when:
  - 설계/연구 문서를 새로 만들거나 크게 개정할 때
  - HTML 템플릿을 손볼 때
  - HTML과 Markdown 중 어느 포맷을 쓸지 결정할 때
---

# HTML Docs Conventions

설계 문서를 **에이전트와 이터레이션**하는 매체. Markdown 한계(100줄 넘어가면 안 읽힌다, ASCII 다이어그램, 색·표·상호작용 표현 어려움)를 HTML로 푼다.

근거: [Thariq Shihipar — The Unreasonable Effectiveness of HTML](https://x.com/trq212/status/2052809885763747935).

## When HTML, When Markdown

| 포맷 | 쓰는 경우 |
|---|---|
| **HTML** | 설계 spec·research·이터레이션 대상 긴 문서. 100줄 넘어가는 문서. 다이어그램/표/하이라이트가 의미를 가짐. 사람이 읽고 의사결정에 쓰는 문서 |
| **Markdown** | 짧은 stable spec (<100줄). 인덱스/라우팅 (`index.md`). 컨벤션·가이드. 코드 옆 README. 작업 메모. grep 대상 |

판단 기준 한 줄: **"다시 읽고 결정에 쓸 문서면 HTML, 기록·라우팅이면 MD"**.

같은 주제를 HTML과 MD로 동시 유지 X. HTML로 옮길 때 MD는 삭제 (git 히스토리에 남음).

## 디자인 시스템 — 한 페이지 정리

`docs/guides/html-doc-template.html`을 복사해서 시작. 토큰/레이아웃/컴포넌트는 그 파일이 source of truth. 아래는 이유와 사용 가이드.

### Color tokens (warm cream family)

```
--ivory      #FAF9F5    페이지 배경. O'Reilly Atlas-like 따뜻한 off-white. 눈 안 아픔
--paper      #FFFFFF    카드/패널 배경. ivory 위에서 살짝 떠 보임
--oat        #E3DACC    tinted callout 배경
--gray-100/150/300/500/700  warm gray scale only (cool gray 금지)
--slate      #141413    primary text — 따뜻한 near-black

단일 accent:
--clay       #D97757    terracotta. 강조 한 가지만. 얇은 line / dot / underline / em italic으로만 사용
보조 accent (semantic일 때만):
--olive      #788C5D    positive / done
--sky        #6A8CAF    secondary data
--error      #B04A3F
```

**규칙**:
- Cool gray(`#f5f5f5` 등) 섞지 마라 — cream 톤이 깨진다
- Accent는 표면을 채우는 데 쓰지 않는다 (border-left, dot, italic, underline에만)
- 새 hue 도입 전에 oat tint(α 10–35%)로 가능한지 먼저 검토

### Typography — three-family combo

```
--serif      "Iowan Old Style", Charter, Palatino, Georgia, serif
--sans       -apple-system, Inter, Segoe UI, system-ui
--mono       ui-monospace, JetBrains Mono, SF Mono, Menlo
```

| 용도 | Family |
|---|---|
| h1, h2, h3.serif, .lede, card h4 | serif |
| body, h3 (uppercase label), nav, UI | sans |
| eyebrow, stage-num, pill, refs, code | mono |

**규칙**:
- 헤딩 weight는 500 (절대 700 안 씀)
- letter-spacing은 헤딩에 `-0.012em`, eyebrow에 `+0.12em`
- 헤딩 안의 `<em>`은 italic + clay 컬러 (em color 기본값 override)
- 본문 16px / line-height 1.65, gray-700
- Lede 22px serif, slate

### Layout — sticky sidebar + content column

```
max-width: 1240px
grid: [sidebar 220px] [gap 64px] [content min 0, max 1fr]
content 내부 prose: max-width 680px (measure)
diagrams/tables/code: content 컬럼 가득 (~860px)
```

- **Sidebar**: `position: sticky; top: 32px`. 항상 보이도록
- **Measure**: 본문 paragraph/list는 680px로 제한해 60–75자 유지. 표·코드·다이어그램만 컬럼 전체 사용
- **Mobile (≤920px)**: 단일 컬럼으로 stack. Sidebar inline at top
- 페이지 padding: `64px 32px 160px` (위·아래 generous, 좌우 적당)

### Spacing rhythm

| 요소 | margin |
|---|---|
| h2 | `56px 0 12px` (다음 단락과 close, 위쪽 generous) |
| h3 | `36px 0 12px` |
| Section gap | `80px` |
| Paragraph | `0 0 14px` |
| List item | `6px 0` |
| Section margin-bottom | implicit via next section margin-top |

이 규칙이 *읽는 호흡*을 만든다 — 섹션 간 큰 호흡, 단락 간 작은 호흡.

### 1.5px borders, rounded corners

- 모든 카드·callout·collapsible은 `1.5px solid var(--gray-300)` (얇은 1px가 아님)
- `border-radius: 12px` 기본, `8px` for small chips
- 1.5px가 "출판물스러운" 느낌의 무게감을 준다

## 표준 컴포넌트

| 컴포넌트 | class | 용도 |
|---|---|---|
| Eyebrow | `.eyebrow` | h1 위의 작은 mono uppercase 라벨 (project · type · status) |
| Lede | `.lede` | h1 직후 serif 22px 인트로 |
| TL;DR | `.tldr` + `.tldr-label` + `.tldr-body` | 머리 직후 핵심 결정/결론. White card + clay border-left |
| Stage num | `.stage-num` | 섹션 머리의 mono `01 · LABEL` |
| Lead | `.lead` | h2 직후 17.5px slate 리드 |
| Note | `.note` | 부가 설명, oat-tinted bg |
| Tip | `.tip` | 강조 콜아웃, white card + clay border-left |
| Refs | `.refs` | 작은 mono 파일 경로 묶음 |
| Facts table | `table.facts` | borderless, 1px row 분리. 좌측 mono header |
| Wide facts | `table.facts.wide` | header 폭 좁힘 |
| Cards grid | `.cards-grid` + `.card` | 옵션 비교, hover lift 효과 |
| Card tag | `.card-tag` | 카드 안의 작은 카테고리 라벨 |
| Pill | `.pill` (`.pill.olive`, `.pill.clay`) | 상태/태그 라벨 |
| Flow list | `ul.flow` | 단계/파이프라인. 화살표 bullet (clay) |
| Diagram | `.diagram-wrap` + SVG + `figcaption` | SVG 다이어그램 wrapper |
| Collapsible | `details.opt` + `.opt-body` | 검토했던 대안, FAQ 등 접기 |

## SVG 다이어그램 가이드

### 색 (palette 일치)

```
.node          fill #FFFFFF, stroke #D1CFC5 1.5px       기본 노드
.node-accent   fill rgba(227,218,204,0.5), stroke #D97757 1.5px   강조
.node-olive    fill rgba(120,140,93,0.15), stroke #788C5D 1.5px   positive/done
.edge          stroke #87867F 1.2px                     일반 흐름
.edge-clay     stroke #D97757 1.5px                     강조 흐름
edge-label     fill #3D3D3A, mono or sans 11px
node-label     fill #141413, 500 13px sans
node-sub       fill #87867F, 400 11px sans
lane           fill #FAF9F5, stroke #D1CFC5
lane-label     fill #87867F, mono 10px uppercase 0.12em
```

### 패턴

- **Swimlane 가로 분리** — 시스템·도메인·시간 단계를 lane으로
- **노드 둥근 모서리** `rx="8"`
- **화살표 marker** — `defs`에 한 번 정의, 재사용
- **labels 위치** — 노드 위/아래는 `font: 500 sans`, 엣지는 mono 또는 sans 11px
- 복잡하면 다이어그램 여러 개로 쪼개기. 하나에 모든 걸 욱여넣지 말 것
- **figcaption** — mono uppercase로 다이어그램 의미 한 줄 명시

### 안 하기

- ASCII art 절대 X — SVG로
- 컬러 폭주 X — 위 palette 안에서만
- 외부 이미지 (PNG/JPG) X — SVG inline만

## 가독성 규칙 (왜 그렇게 하나)

### 측정 가능한 가독성 원리

1. **Line measure 60–75자** — 그 이상이면 눈이 다음 줄 못 찾고, 그 이하면 호흡이 짧다. 16–17px font에서 ~680px가 그 자리
2. **Line-height 1.6–1.7** — 본문. 헤딩은 1.1–1.3 (조밀)
3. **Cream bg + warm dark text** — pure white + black은 명도 대비 21:1로 과도, 눈이 빨리 피곤. cream(#FAF9F5) + slate(#141413)는 ~18:1로 충분 + 따뜻
4. **Letter-spacing** — 본문은 0, 헤딩은 살짝 tighter(`-0.012em`), uppercase eyebrow는 wider(`+0.12em`)
5. **수직 호흡** — 섹션 간 80px, h2-단락 12px, 단락 간 14px. 큰 호흡이 정보 chunk 경계를 시각화
6. **Hierarchy via family + size + transform** — 같은 색·weight라도 serif/sans/mono 전환으로 정보 layer 분리
7. **Anchor accent** — clay 하나가 모든 강조를 캐리 — 시각적 통일성

### 읽기 ergonomics

- **Sticky sidebar TOC** — 긴 문서에서 위치 감각·점프 가능
- **Section 번호 mono** — 본문(serif/sans)과 시각적으로 분리되어 "현재 위치" 빠른 인식
- **흰 카드 floating on cream** — 정보 chunk 경계가 자연스럽게 보임
- **Code block separator** — pre는 white bg + 1.5px border로 단락과 시각 분리
- **표는 borderless** — 1px row 분리만으로 충분, 격자 강박 X

## Iteration Workflow

1. **만들기**: 템플릿 복사 → 내용 작성. 첫 draft는 텍스트 위주, 다이어그램은 placeholder
2. **열기**: `open <file>.html` → 브라우저
3. **반복**: 에이전트에게 *"§3 federation 다이어그램 추가"*, *"§5 표 두 칼럼 추가"* 같이 부분 수정 지시
4. **공유**: 로컬 file:// 경로 또는 S3 업로드 후 링크
5. **확정**: 결정 굳어지면 `<details class="opt">` 안에 검토했던 대안 짧게 보존

이터레이션 중에는 본문이 좀 더러워도 OK. 확정 시점에 정리.

## What Goes Where

| Type | Format |
|---|---|
| 루팅/인덱스 | MD |
| 컨벤션/가이드 (이 문서, backend-conventions 등) | MD |
| CLAUDE.md / AGENTS.md | MD |
| 로드맵·체크리스트 | MD |
| 설계 문서 (`docs/architecture/`) | HTML |
| 연구 문서 (`docs/research/`) | HTML |
| 현행 spec (`{service}/docs/spec/`) | 단순 reference는 MD, 복잡 design은 HTML |

## Frontmatter — HTML에서는 `<meta>`

```html
<meta name="monomer:status" content="draft" />
<meta name="monomer:summary" content="한 줄 요약" />
<meta name="monomer:related" content="architecture/workspace-spec.md, roadmap.html" />
```

`docs-lint`가 이를 읽음 (현 시점 미구현 — 추후 추가).

## Trade-offs

- **Git diff noisy**: HTML diff는 MD보다 시끄럽다. 큰 구조 변경은 commit message로 의도 명시
- **토큰 비용**: HTML이 토큰 더 씀. Opus context window에선 무시 수준
- **생성 시간**: HTML이 2–4x 오래 걸림. 다 읽힐 가치가 있다면 감수
- **에이전트 수정 비용**: 부분 수정은 MD가 쉬움. HTML은 컴포넌트 자리 명확해야 → 컨벤션 따르는 게 필수

## See also

- [docs-evolution.md](docs-evolution.md) — docs 계층, lifecycle, frontmatter
- [html-doc-template.html](html-doc-template.html) — 복사용 템플릿 (source of truth)
