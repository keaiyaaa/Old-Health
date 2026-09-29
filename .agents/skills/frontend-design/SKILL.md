---
name: frontend-design
description: Create distinctive, production-grade frontend interfaces with high design quality. Use this skill when the user asks to build web components, pages, artifacts, posters, or applications (examples include websites, landing pages, dashboards, React components, HTML/CSS layouts, or when styling/beautifying any web UI). Generates creative, polished code and UI design that avoids generic AI aesthetics.
license: Complete terms in LICENSE.txt
---

> **本副本已按本项目规范裁剪**（2026-09-28，见 AGENTS.md 3.1）：
> 保留"避免通用 AI 风格"的全部要求；删除"必须大胆、极端风格、反极简"的主张。
> 本项目的设计方向已由 `docs/frontend/原型设计说明书.md` 锁定：**白 + 绿、安静克制、全站无红色、适老化硬指标**。
> 设计方向不重新发散，Skill 的职责是在这个既定方向内把细节执行到位。

This skill guides creation of distinctive, production-grade frontend interfaces that avoid generic "AI slop" aesthetics. Implement real working code with exceptional attention to aesthetic details and creative choices.

The user provides frontend requirements: a component, page, application, or interface to build. They may include context about the purpose, audience, or technical constraints.

## Design Thinking

Before coding, understand the context and commit to a clear, intentional design direction:

- **Purpose**: What problem does this interface solve? Who uses it?
- **Direction**: 本项目方向已定 —— 白 + 绿、安静克制、适老。不另起炉灶；所有创意投入用于把既定方向执行得精致。
- **Constraints**: Technical requirements (framework, performance, accessibility)，以及第 6 节合规红线（无红色、禁用词、老人端零指标）。
- **Differentiation**: 让界面"被记住"靠的是细节的完成度 —— 间距节奏、状态处理的周全、动效的克制 —— 而不是视觉强度。

**CRITICAL**: Intentionality, not intensity. 极简要做得精致，克制要做得考究；平庸的"默认样式"才是唯一不可接受的。

Then implement working code (HTML/CSS/JS, React, Vue, Compose 等) that is:
- Production-grade and functional
- Cohesive with the established design system
- Meticulously refined in every detail

## Frontend Aesthetics Guidelines

Focus on:
- **Typography**: 在项目既有字体框架内把层级、字重、行高做准。中文界面字体选择受系统限制时，用字号/字重/间距建立层级，避免依赖字体本身求"独特"。
- **Color & Theme**: 严格使用 `docs/frontend/原型设计说明书.md` 4.1 与 `ui/theme/Color.kt` 的设计令牌。主色主导 + 少量强调色优于平均分配；**不引入令牌之外的新颜色**（全站无红色是红线）。
- **Motion**: 动效服务于反馈与引导，幅度克制（老人端尤其如此）；支持系统"减少动态效果"。CSS/Compose 原生方案优先，不为动效引入新依赖。
- **Spatial Composition**: 间距遵循 4/8/12/16/24/32 节奏；留白是设计手段，不加无意义的装饰元素。
- **Backgrounds & Visual Details**: 用底色层次（BgPage/BgCard/BgSubtle）、描边、圆角建立深度；保持"干净、安静"的整体基调。

NEVER use generic AI-generated aesthetics: overused default font stacks without hierarchy, cliched color schemes (particularly purple gradients on white backgrounds), predictable cookie-cutter component patterns, and design that lacks context-specific character. **每个界面都应能看出"为这个产品、这群用户专门做的"**。

**IMPORTANT**: Match implementation complexity to the aesthetic vision. 本项目是 refined minimalism：需要的是 restraint, precision, and careful attention to spacing, typography, and subtle details. Elegance comes from executing the vision well.

Remember: 生产级界面的最高标准是"每个细节都是有意为之"，而不是"视觉上最响"。
