import { mountSlotPlayground } from '/agent-views/_shared/slotPlayground.js';

/** Visual Identity agent's Playground view — the shared SLOT runner plus palette/typography rendering. */
export function mount(container, agent, ctx) {
  return mountSlotPlayground(container, {
    agentId: 'visual-identity',
    resultField: 'visualIdentity',
    renderOutput: (o, h) => `
      ${palette(o.colorPalette, h)}
      ${typography(o.typography, h)}
      ${h.block('Mood direction', o.moodDirection)}`,
    renderAudit: (o, h) => `
      <div class="vi-swatch-dots">${(o.colorPalette || []).map(c =>
        `<span class="vi-swatch-dot" title="${h.esc(c.hex)}" style="background:${h.esc(c.hex)}"></span>`).join('')}</div>
      <div class="slp-hint">${h.esc(o.typography?.headlineFont)} / ${h.esc(o.typography?.bodyFont)}</div>`,
  }, ctx);
}

function palette(colors, h) {
  if (!colors || !colors.length) return '';
  return `
    <div class="slp-block">
      <div class="slp-label">Colour palette</div>
      <div class="vi-palette">${colors.map(c => `
        <div class="vi-swatch">
          <div class="vi-swatch-color" style="background:${h.esc(c.hex)}"></div>
          <div class="vi-swatch-info">
            <div class="vi-swatch-name">${h.esc(c.name)}<span class="vi-swatch-hex">${h.esc(c.hex)}</span></div>
            <div class="vi-swatch-role">${h.esc(c.role)}</div>
            <div class="vi-swatch-rationale">${h.esc(c.rationale)}</div>
          </div>
        </div>`).join('')}</div>
    </div>`;
}

function typography(t, h) {
  if (!t) return '';
  return `
    <div class="slp-block">
      <div class="slp-label">Typography</div>
      <div class="vi-typo">
        <div class="vi-typo-role"><span class="vi-typo-label">Headline</span><span class="vi-typo-font">${h.esc(t.headlineFont)}</span></div>
        <div class="vi-typo-role"><span class="vi-typo-label">Body</span><span class="vi-typo-font">${h.esc(t.bodyFont)}</span></div>
      </div>
      <div class="vi-typo-rationale">${h.esc(t.pairingRationale)}</div>
    </div>`;
}
