---
name: GlycoCalm Health System
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#44474c'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#74777d'
  outline-variant: '#c4c6cc'
  surface-tint: '#525f71'
  primary: '#000000'
  on-primary: '#ffffff'
  primary-container: '#0f1c2c'
  on-primary-container: '#778598'
  inverse-primary: '#bac8dc'
  secondary: '#006b55'
  on-secondary: '#ffffff'
  secondary-container: '#6dfad2'
  on-secondary-container: '#00725b'
  tertiary: '#000000'
  on-tertiary: '#ffffff'
  tertiary-container: '#001c38'
  on-tertiary-container: '#1286e5'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#d6e4f9'
  primary-fixed-dim: '#bac8dc'
  on-primary-fixed: '#0f1c2c'
  on-primary-fixed-variant: '#3a4859'
  secondary-fixed: '#6dfad2'
  secondary-fixed-dim: '#4bddb7'
  on-secondary-fixed: '#002018'
  on-secondary-fixed-variant: '#005140'
  tertiary-fixed: '#d3e4ff'
  tertiary-fixed-dim: '#a2c9ff'
  on-tertiary-fixed: '#001c38'
  on-tertiary-fixed-variant: '#004881'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
typography:
  display-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 3rem
    fontWeight: '800'
    lineHeight: 3.5rem
    letterSpacing: -0.03em
  display-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 2rem
    fontWeight: '700'
    lineHeight: 2.5rem
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 1.5rem
    fontWeight: '700'
    lineHeight: 2rem
    letterSpacing: -0.015em
  headline-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 1.125rem
    fontWeight: '600'
    lineHeight: 1.625rem
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 1rem
    fontWeight: '500'
    lineHeight: 1.5rem
  body-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.875rem
    fontWeight: '400'
    lineHeight: 1.25rem
  metric-value:
    fontFamily: Plus Jakarta Sans
    fontSize: 1.875rem
    fontWeight: '800'
    lineHeight: 2.25rem
    letterSpacing: -0.02em
  metric-unit:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.75rem
    fontWeight: '600'
    lineHeight: 1rem
    letterSpacing: 0.02em
  label-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.8125rem
    fontWeight: '600'
    lineHeight: 1.125rem
  label-xs:
    fontFamily: Plus Jakarta Sans
    fontSize: 0.6875rem
    fontWeight: '500'
    lineHeight: 0.875rem
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  space-2xs: 0.25rem
  space-xs: 0.5rem
  space-sm: 0.75rem
  space-md: 1rem
  space-lg: 1.25rem
  space-xl: 1.5rem
  space-2xl: 2rem
  card-padding: 1.25rem
  sheet-padding: 1.5rem
  grid-gutter: 0.875rem
---

## Brand & Style

This design system is tailored for medical technology, continuous glucose monitoring (CGM), and chronic metabolic care (Ambulatory Glucose Profile / AGP). Living with diabetes requires navigating endless metrics, risk calculations, and alert fatigue; therefore, the visual identity intentionally avoids clinical sterility and alarmist aesthetics in favor of a soothing, reassuring, and premium digital sanctuary.

### Design Persona & Target Audience
- **Audience:** Individuals managing Type 1 and Type 2 diabetes, gestational diabetes, endocrinologists, caregivers, and health-conscious athletes.
- **Tone:** Scientific yet empathetic, calm, precise, unobtrusive, and empowering. It balances high-fidelity clinical rigor with the tactile elegance of consumer wellness products like Apple Health, Whoop, and Oura.
- **Design Movement:** **Modern Tonal / Soft Precision**. Characterized by serene warm-slate and porcelain surfaces, soft pill shapes, gentle layered elevation with diffused natural lighting, and purposeful functional semantic color accents that communicate biometric states instantly without causing anxiety.

## Colors

The palette transforms medical urgency into calm, digestible guidance:

- **Primary (`#0D1B2A` - Deep Obsidian Slate):** Used for primary buttons, prominent numeric readouts, and primary headers. It conveys uncompromising precision and grounding stability.
- **Secondary (`#00B894` - Emerald Mint):** The hallmark "In-Range / Target" color (TIR 70–180 mg/dL). Fresh, encouraging, and highly legible against both light card backgrounds and deep backgrounds.
- **Tertiary (`#0984E3` - Electric Cerulean):** Applied to secondary metrics (GMI/HbA1c), baseline timeline indicators, and interactive analytical controls.
- **Neutral (`#64748B` - Cool Slate):** Provides subtle typography hierarchy for unit metrics (`mg/dL`, `mmol/L`, `U`), card subheadings, and secondary metadata.

### Clinical Semantic Color Accents (AGP Standard)
- **Target / In Range (70–180 mg/dL):** `#00B894` (Emerald Mint)
- **High (181–250 mg/dL):** `#F59E0B` (Warm Honey Amber)
- **Very High (>250 mg/dL):** `#F97316` (Vibrant Coral Orange)
- **Low / Hypo (<70 mg/dL):** `#F43F5E` (Gentle Crimson Rose)

Backgrounds rely on a two-tier hierarchy: a soft, muted slate container background (`#F1F5F9`) holding pristine floating cards (`#FFFFFF` and `#F8FAFC`).

## Typography

**Plus Jakarta Sans** is utilized universally across headlines, body text, and numeric metric displays. Its modern geometric structure, rounded terminals, and clear character apertures prevent reading errors in time-critical medical scenarios.

- **Numerics & Vital Readouts:** Rendered with `metric-value` (`font-weight: 800`) paired immediately with `metric-unit` in subdued slate to clarify values (e.g., `134 mg/dL`, `6.5%`, `21.6% CV`).
- **Tabular Figures:** For time-series tables, live continuous glucose readings, and delta trackers (`+4 mg/dL/min`), enable `font-variant-numeric: tabular-nums` to maintain stability during real-time value updates.
- **Labels & Microcopy:** Category badges and guideline limits (e.g., `Meta < 7.0%`, `Estável (Meta < 36%)`) use medium or semibold weights with slight tracking adjustments to maximize glanceability.

## Layout & Spacing

The system leverages a compact, fluid 4-column layout on mobile devices and an 8-to-12 column adaptive grid for tablets and web dashboards.

- **Rhythm & Grid:** Built on an 8pt base grid with a 4pt sub-grid for badge chips, micro-labels, and progress track heights.
- **Metric Tiles:** Arranged in balanced 2x2 grid blocks on handheld screens with a tight gutter (`0.875rem` / `14px`), maximizing immediate vertical visibility without excessive scroll.
- **Bottom Sheets & Modal Overlays:** Elevated with 24px top-radius sheets and safe-area auto-insets for ergonomic thumb reach on mobile CGM usage.

## Elevation & Depth

Visual hierarchy uses **ambient diffused shadows** and **tonal contrast** rather than stark borders:

- **Level 0 (Canvas Base):** Solid soft tint (`#E2E8F0` to `#F1F5F9`).
- **Level 1 (Clinical Cards & AGP Tiles):** Crisp white (`#FFFFFF`) with ultra-diffused atmospheric ambient drop: `0px 4px 18px -2px rgba(15, 23, 42, 0.04), 0px 1px 3px rgba(15, 23, 42, 0.02)`.
- **Level 2 (Active Modals, Sheets, Action Bars):** Elevated floating layers featuring a subtle hairline perimeter: `box-shadow: 0px 16px 36px -8px rgba(15, 23, 42, 0.08)` coupled with an inner border of `1px solid rgba(255, 255, 255, 0.8)`.
- **Level 3 (Toasts & Critical Hypo/Hyper Alerts):** High-prominence float with directional blur: `0px 20px 40px -10px rgba(15, 23, 42, 0.16)`.

## Shapes

The shape system adopts a **generous, friendly roundedness** to soften medical data:

- **Metric Cards & Content Blocks:** `1.25rem` (20px) to `1.5rem` (24px) border radius, giving analytical panels an approachable, pebble-like contour.
- **Buttons & Core CTAs:** Pill-shaped (`9999px`) or hyper-rounded `1rem` (16px) corners for tactile comfort.
- **Progress & AGP Distribution Bars:** Fully rounded capsule tracks (`9999px`) to create seamless, continuous multi-segmented metric strips.
- **Icon Badges & Dismiss Buttons:** Circular (`9999px`) with soft tinted backgrounds.

## Components

### Buttons
- **Primary CTA:** High-contrast obsidian slate (`#0D1B2A`) fill, crisp white semibold typography, height `52px`, `rounded-full` or `rounded-2xl`, with smooth active scale transitions (`scale(0.98)`).
- **Secondary / Close Action:** Low-contrast neutral tint (`#E2E8F0` or `#F1F5F9`) with slate icon or text, ensuring non-distracting navigation dismissals.

### AGP Segmented Range Bar
- A continuous horizontal capsule bar (`height: 14px`, `rounded-full`) showing proportions of **In-Range** (`#00B894`), **High** (`#F59E0B`), **Very High** (`#F97316`), and **Low** (`#F43F5E`).
- Paired with an inline legend featuring colored circular pips (`8px`), semibold category names, and bold percentage indicators.

### Metric Cards (AGP Vital Tiles)
- **Surface:** Pure white `#FFFFFF` surface with `rounded-2xl` corners and `card-padding`.
- **Header:** Uppercase or sentence-case label in `#64748B` (`fontSize: 0.8125rem`).
- **Primary Data:** Large numeric stat (`1.875rem`, bold) with colored feedback (e.g. mint for in-target GMI and CV, obsidian for glucose average).
- **Footer / Status:** Small badge or guideline cue (`Meta < 7.0%`, `Estável (Meta < 36%)`) using secondary muted green or neutral slate.

### Chips & Badges
- Soft-tinted backgrounds derived from functional colors with `12%` opacity (e.g., `#00B8941F` for target badges) combined with full-saturation text.
- Height `24px` to `28px`, `rounded-full`, with compact horizontal padding (`8px` to `12px`).

### Input Fields & Controls
- Clean `48px` inputs with `#F8FAFC` filled backgrounds, `1px solid #E2E8F0` border, transitions to `#00B894` on focus with a 3px soft mint outer glow.
