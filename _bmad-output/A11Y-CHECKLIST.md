---
audience: frontend-dev, designer, PM
project: side-project
date: 2026-07-06
how-to-use: WCAG 2.1 AA accessibility checklist. Apply during frontend development + PR review.
---

# Accessibility Checklist — side-project (WCAG 2.1 AA)

> **Standard:** WCAG 2.1 Level AA (per addendum A3).
> **Note:** Not in v1 core spec but recommended for v1.1+ — and required for many jurisdictions (EU, US public sector).
> **Tools:** `axe-core` (Linter), `Pa11y` (CI check), `NVDA` / `VoiceOver` (screen reader testing).

---

## 1. The 4 POUR principles

WCAG 2.1 is organized around 4 principles (POUR):

- **Perceivable:** Users can perceive the content (sighted, blind, low vision, deaf)
- **Operable:** Users can interact (mouse, keyboard, touch, voice)
- **Understandable:** Content is readable and predictable
- **Robust:** Works with current + future tools (assistive tech)

---

## 2. Perceivable

### 1.1 Text alternatives

- [ ] All non-text content has a text alternative (alt text on images, captions on videos, transcripts on audio)
  - React: `alt={description}` on `<img>`; `<img alt="" />` for decorative images
- [ ] Alt text is **meaningful** (not "image1.jpg" — describe the function)
- [ ] Decorative images have `alt=""`
- [ ] Complex images (charts, graphs) have a longer description

### 1.2 Time-based media

- [ ] Videos have captions (auto + manual)
- [ ] Audio has transcripts
- [ ] Live media has live captions

### 1.3 Adaptable

- [ ] Page has correct heading structure (h1 → h2 → h3, no skipping)
- [ ] Reading order is logical (CSS doesn't break reading order)
- [ ] Form fields have `<label>` (not just placeholder)
- [ ] Form errors are programmatically associated with their fields (`aria-describedby`)
- [ ] Layout works at 200% zoom (per addendum A3)
- [ ] Layout works with screen reader (test with NVDA / VoiceOver)

### 1.4 Distinguishable

- [ ] **Color contrast:** 4.5:1 for normal text, 3:1 for large text (WCAG AA)
  - Test: https://webaim.org/resources/contrastchecker/
- [ ] Don't rely on color alone (e.g., red for error must also have icon or text)
- [ ] Text is resizable to 200% without breaking layout
- [ ] Images of text are avoided (use real text)
- [ ] No auto-playing audio (or provide clear pause control)

---

## 3. Operable

### 2.1 Keyboard accessible

- [ ] All functionality is keyboard-accessible
  - Tab, Shift+Tab, Enter, Space, Arrow keys, Esc
- [ ] No keyboard traps (focus can always escape)
- [ ] Visible focus indicators (don't remove outline without replacement)
- [ ] Skip navigation link (for screen reader / keyboard users)
- [ ] Custom components (dropdowns, modals) support keyboard

### 2.2 Enough time

- [ ] No time limits on form completion (or extendable)
- [ ] No auto-advancing carousels (or pause button)
- [ ] No auto-refresh that interrupts user input

### 2.3 Seizures and physical reactions

- [ ] No content flashes more than 3 times per second (WCAG 2.3.1)

### 2.4 Navigable

- [ ] Page has descriptive title (`<title>` tag)
- [ ] Page has descriptive headings
- [ ] Focus order is logical
- [ ] Link text is descriptive (not "click here" — say "view order details")
- [ ] Multiple ways to find a page (search, navigation, sitemap)
- [ ] Focus indicators are visible
- [ ] Section headings describe the section

### 2.5 Input modalities

- [ ] Pointer gestures (drag, pinch) have keyboard / single-pointer alternative
- [ ] Activation on up-event (mousedown doesn't trigger; mouseup does)
- [ ] Motion-actuated content has UI alternative (e.g., shake-to-undo)
- [ ] Focus visible on all focusable elements

---

## 4. Understandable

### 3.1 Readable

- [ ] Page language is set (`<html lang="vi">` or `lang="en">`)
- [ ] Language of parts is set (`<span lang="en">Hello</span>`)
- [ ] Unusual words are explained (glossary or inline)
- [ ] Abbreviations are expanded

### 3.2 Predictable

- [ ] No unexpected context changes (no popups, no auto-redirects without warning)
- [ ] Form submission doesn't change context unexpectedly
- [ ] Form re-submission is safe (no duplicate payments)
- [ ] Navigation is consistent across the site

### 3.3 Input assistance

- [ ] Form errors are clearly identified
- [ ] Form labels are clear and descriptive
- [ ] Error prevention for legal / financial / data-deletion forms (confirm step, undo)
- [ ] Form submission feedback (success / failure)
- [ ] Required fields are clearly marked

---

## 5. Robust

### 4.1 Compatible

- [ ] HTML is valid (no unclosed tags, no missing attributes)
- [ ] ARIA attributes are used correctly (per WAI-ARIA spec)
- [ ] Custom components expose role + state to assistive tech
- [ ] JavaScript doesn't break screen reader (test with NVDA / VoiceOver)
- [ ] Status messages (toast, alert) are announced to screen readers (`aria-live="polite"`)

---

## 6. Implementation by component

### Forms (cart, checkout, login, register, address, etc.)

- [ ] Every `<input>` has a `<label for="...">` 
- [ ] Required fields are marked with `aria-required="true"` and visual `*`
- [ ] Errors are associated with fields via `aria-describedby`
- [ ] Submit button is a real `<button type="submit">` (not `<div onClick>`)
- [ ] Loading state is announced (e.g., `aria-busy="true"`)
- [ ] Success state is announced
- [ ] Tab order is logical (no jumps)
- [ ] Form is keyboard-navigable end-to-end

### Modals / dialogs

- [ ] Use `<dialog>` element (HTML5) with `showModal()` (not custom div)
- [ ] Focus is trapped within modal (Tab doesn't escape)
- [ ] Esc closes modal
- [ ] Background content has `aria-hidden="true"` when modal is open
- [ ] Modal has `aria-labelledby` referencing title
- [ ] Modal has `aria-describedby` for description

### Tables (admin views, order history)

- [ ] Use `<table>`, `<thead>`, `<tbody>`, `<th>`, `<td>` correctly
- [ ] `<th>` has `scope="col"` or `scope="row"`
- [ ] Tables have a `<caption>` describing the data
- [ ] Don't use tables for layout (use CSS Grid / Flexbox)

### Forms (Vietnamese diacritics)

- [ ] `lang="vi"` attribute on HTML
- [ ] Form fields support Vietnamese characters
- [ ] Error messages in Vietnamese when user prefers
- [ ] Auto-complete (browser's built-in) is supported

### Navigation

- [ ] Skip-to-content link (visually hidden until focused)
- [ ] Nav has `<nav>` + `aria-label="Primary"` etc.
- [ ] Breadcrumbs for deep navigation
- [ ] Footer nav with all secondary links
- [ ] Pagination has clear "Previous" / "Next" / "Page X of Y" + `aria-label`

### Cart / order summary

- [ ] Total price is in `<output>` or `aria-live` (so screen reader announces changes)
- [ ] Quantity buttons have `aria-label="Increase quantity"` (etc.)
- [ ] Remove button has `aria-label="Remove X from cart"`
- [ ] Empty state is announced (e.g., "Your cart is empty")

### Login / auth

- [ ] "Forgot password" link is clear
- [ ] "Show password" toggle (with `aria-pressed`)
- [ ] CAPTCHA has audio alternative (per WCAG)
- [ ] Error messages don't reveal whether email exists (security)

### Payment (PCI-DSS + WCAG)

- [ ] Stripe Elements iframe is keyboard-navigable
- [ ] Card error messages are clear
- [ ] Total amount is announced to screen reader
- [ ] Submit button is keyboard-accessible
- [ ] Loading state is announced
- [ ] Error messages are read by screen reader

### Vietnamese tax invoice

- [ ] PDF download link has descriptive text
- [ ] PDF itself should be tagged PDF (WCAG)
- [ ] Download progress is announced

---

## 7. Testing workflow

### Manual testing (per release)

- [ ] Tab through entire app — only one focusable element at a time
- [ ] Test with screen reader (NVDA on Windows, VoiceOver on macOS / iOS)
- [ ] Test at 200% zoom — no horizontal scroll, no overlapping content
- [ ] Test with browser zoom + OS-level zoom (Windows Magnifier, macOS Zoom)
- [ ] Test with high contrast mode (Windows High Contrast, macOS Increase Contrast)
- [ ] Test with dark mode
- [ ] Test with keyboard only (no mouse)

### Automated testing (per CI)

- [ ] **Pa11y** runs in CI on every PR
  - `pa11y-ci --json https://staging.example.com/ > pa11y-report.json`
- [ ] **axe-core** runs in unit tests
  - `expect(axe(container)).toHaveNoViolations();`
- [ ] **Lighthouse CI** for accessibility score (target: ≥ 90)
- [ ] No critical / serious violations block merge

### Lighthouse CI config

```yaml
# .lighthouserc.json
{
  "ci": {
    "collect": {
      "url": [
        "http://localhost:3000/",
        "http://localhost:3000/products",
        "http://localhost:3000/cart",
        "http://localhost:3000/checkout"
      ]
    },
    "assert": {
      "assertions": {
        "categories:accessibility": ["error", {"minScore": 0.9}]
      }
    }
  }
}
```

---

## 8. Color + typography

### Color contrast

| Foreground | Background | Ratio | Status |
|---|---|---|---|
| Black (#000) | White (#fff) | 21:1 | ✅ AAA |
| Dark gray (#333) | White (#fff) | 12.6:1 | ✅ AAA |
| Mid gray (#666) | White (#fff) | 5.7:1 | ✅ AA |
| Light gray (#999) | White (#fff) | 2.85:1 | ❌ FAIL AA |

### Color blindness

- 8% of males have some form of color blindness
- Don't rely on color alone (use icons + text)
- Test: https://www.color-blindness.com/coblis-color-blindness-simulator/

### Typography

- [ ] Base font size ≥ 16px
- [ ] Line height ≥ 1.5 for body text
- [ ] Letter spacing adequate (no compressed text)
- [ ] Font is readable (avoid decorative fonts for body)
- [ ] Use system font stack (works with OS settings)
- [ ] Respect user's font-size preferences (use rem, not px)

---

## 9. Mobile + responsive

- [ ] Touch targets ≥ 44x44px (Apple HIG) or 48x48px (WCAG)
- [ ] Pinch-to-zoom is not disabled (`<meta name="viewport" content="user-scalable=yes">`)
- [ ] Orientation works in portrait + landscape
- [ ] No horizontal scroll on small screens
- [ ] Form inputs are easy to use on mobile (auto-correct, autocomplete attributes)

---

## 10. Internationalization (per Vietnamese-first)

- [ ] `lang="vi"` on HTML element
- [ ] All form labels in user's language (FR-60 per architecture)
- [ ] Error messages in user's language
- [ ] Currency formatting respects locale (per addendum A3)
- [ ] Date formatting respects locale
- [ ] Right-to-left languages supported (per NFR-I18N-3, even though v1 is vi/en)

---

## 11. Component-level checklist (per React component)

### Button

```jsx
// ❌ WRONG
<div onClick={handleClick}>Submit</div>

// ✅ RIGHT
<button type="submit" aria-label="Submit order">
  Submit
</button>
```

### Link

```jsx
// ❌ WRONG
<a onClick={...}>Click here</a>

// ✅ RIGHT
<a href="/cart" aria-label="View cart with 3 items">
  Cart (3)
</a>
```

### Image

```jsx
// ❌ WRONG
<img src="product.jpg" />

// ✅ RIGHT
<img
  src="product.jpg"
  alt="iPhone 15 Pro in natural titanium color, front view"
/>

// Decorative
<img src="spacer.png" alt="" role="presentation" />
```

### Form field

```jsx
// ❌ WRONG
<input type="text" placeholder="Email" />

// ✅ RIGHT
<label htmlFor="email">Email</label>
<input
  id="email"
  type="email"
  required
  aria-required="true"
  aria-invalid={hasError ? "true" : "false"}
  aria-describedby={hasError ? "email-error" : undefined}
/>
{hasError && (
  <span id="email-error" role="alert">
    Please enter a valid email
  </span>
)}
```

### Loading state

```jsx
// ❌ WRONG
{isLoading && <div>Loading...</div>}

// ✅ RIGHT
{isLoading && (
  <div role="status" aria-live="polite">
    Loading...
  </div>
)}
```

---

## 12. Per-sprint A11Y tasks

| Sprint | A11Y task |
|---|---|
| 1 | Set up axe-core + Pa11y in CI (Story 1.4 admin UI) |
| 2 | Add skip-to-content link (cart + checkout) |
| 3 | Add keyboard nav for Stripe Elements (per FR-79) |
| 4 | A11y test order timeline (Story 4.3) |
| 5 | A11y test PDPD export UI (Story 5.2) |
| 6 | A11y test Vietnamese search input (Story 6.1) |
| 7 | A11y test RMA UI (Story 7.1) |
| 8 | A11y test admin UI (Story 8.1) |
| 9 | A11y test invoice download (Story 9.2) |
| 10 | Final A11y audit + Lighthouse CI ≥ 90 |

---

## 13. Cross-references

- **Architecture (frontend):** `architecture-detail.md` §"Detail: ADR-10"
- **Frontend handbook:** `FRONTEND-HANDBOOK.md`
- **Vietnamese-first:** `COMPLIANCE-VN.md` §6
- **PR conventions:** `CONTRIBUTING.md` (CI checks)
- **Per-sprint work:** `SPRINT-1-DEV-HANDBOOK.md`
- **Onboarding:** `AGENT-ONBOARDING.md`
- **Glossary:** `GLOSSARY.md`
