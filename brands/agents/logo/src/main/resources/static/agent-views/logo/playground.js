import { mountSlotPlayground } from '/agent-views/_shared/slotPlayground.js';

const METHOD_TITLES = { DALLE: 'Primary lockup (OpenAI image)', IDEOGRAM: 'Primary lockup (Ideogram)', SVG_CONCEPT: 'SVG concept' };

/** Logo agent's Playground view — the shared SLOT runner plus how to render a logo mark. */
export function mount(container, agent, ctx) {
  return mountSlotPlayground(container, {
    agentId: 'logo',
    resultField: 'logo',
    renderOutput: (o, h) => {
      const isImage = o.method === 'DALLE' || o.method === 'IDEOGRAM' || (!o.svgMarkup && o.imageUrl);
      // svgMarkup is the agent's own generated SVG, rendered as-is (as the Playground always has)
      const visual = isImage ? `<img src="${h.esc(o.imageUrl)}" alt="Generated logo">` : (o.svgMarkup || '');
      const symbol = o.symbolImageUrl
        ? `<div class="slp-block"><div class="slp-label">Symbol</div>${h.assetFrame(`<img src="${h.esc(o.symbolImageUrl)}" alt="Logo symbol">`)}</div>`
        : '';
      const others = (o.candidateImageUrls || []).filter(u => u !== o.imageUrl);
      const candidates = others.length
        ? `<div class="slp-block"><div class="slp-label">Candidates not chosen</div><div class="lg-candidates">${
            others.map(u => `<img class="lg-candidate" src="${h.esc(u)}" alt="Candidate lockup">`).join('')}</div></div>`
        : '';
      return `
        <div class="slp-block">
          <div class="slp-label">${h.esc(METHOD_TITLES[o.method] || 'Logo mark')}</div>
          ${h.assetFrame(visual)}
        </div>
        ${symbol}
        ${h.block('Why this one', o.selectionRationale)}
        ${candidates}
        ${h.block('Concept', o.conceptDescription)}
        ${h.block('Symbolism', o.symbolism)}
        ${h.block('Symbol', o.symbolDescription)}
        ${isImage ? h.block('Image prompt used', o.imagePrompt) : ''}`;
    },
    renderAudit: (o, h) => o.imageUrl
      ? `<img class="lg-audit-img" src="${h.esc(o.imageUrl)}" alt="Logo">`
      : `<div class="slp-hint slp-italic">${h.esc(o.conceptDescription || 'No image generated')}</div>`,
  }, ctx);
}
