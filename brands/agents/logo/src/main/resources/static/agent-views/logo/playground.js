import { mountSlotPlayground } from '/agent-views/_shared/slotPlayground.js';

const METHOD_TITLES = { DALLE: 'Generated mark (DALL-E)', IDEOGRAM: 'Generated mark (Ideogram)', SVG_CONCEPT: 'SVG concept' };

/** Logo agent's Playground view — the shared SLOT runner plus how to render a logo mark. */
export function mount(container, agent, ctx) {
  return mountSlotPlayground(container, {
    agentId: 'logo',
    resultField: 'logo',
    renderOutput: (o, h) => {
      const isImage = o.method === 'DALLE' || o.method === 'IDEOGRAM' || (!o.svgMarkup && o.imageUrl);
      // svgMarkup is the agent's own generated SVG, rendered as-is (as the Playground always has)
      const visual = isImage ? `<img src="${h.esc(o.imageUrl)}" alt="Generated logo">` : (o.svgMarkup || '');
      return `
        <div class="slp-block">
          <div class="slp-label">${h.esc(METHOD_TITLES[o.method] || 'Logo mark')}</div>
          ${h.assetFrame(visual)}
        </div>
        ${h.block('Concept', o.conceptDescription)}
        ${h.block('Symbolism', o.symbolism)}
        ${isImage ? h.block('Image prompt used', o.imagePrompt) : ''}`;
    },
    renderAudit: (o, h) => o.imageUrl
      ? `<img class="lg-audit-img" src="${h.esc(o.imageUrl)}" alt="Logo">`
      : `<div class="slp-hint slp-italic">${h.esc(o.conceptDescription || 'No image generated')}</div>`,
  }, ctx);
}
