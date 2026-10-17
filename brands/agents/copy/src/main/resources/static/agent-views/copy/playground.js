import { mountSlotPlayground } from '/agent-views/_shared/slotPlayground.js';

/** Copy agent's Playground view — the shared SLOT runner plus how to render copy output. */
export function mount(container, agent, ctx) {
  return mountSlotPlayground(container, {
    agentId: 'copy',
    resultField: 'copy',
    renderOutput: (o, h) => `
      ${h.block('Tagline', o.tagline, true)}
      ${h.block('Mission statement', o.missionStatement)}
      ${h.block('Brand story', o.brandStory)}
      ${h.block('Elevator pitch', o.elevatorPitch)}
      ${toneGuide(o.toneGuide, h)}`,
    renderAudit: (o, h) => `
      ${h.block('Tagline', o.tagline, true)}
      ${h.block('Mission statement', o.missionStatement)}`,
  }, ctx);
}

function toneGuide(tg, h) {
  if (!tg) return '';
  const examples = (tg.examples || []).map(ex => `
    <div class="cp-example">
      <div class="cp-do">✓ ${h.esc(ex.doThis)}</div>
      <div class="cp-not">✕ ${h.esc(ex.notThis)}</div>
    </div>`).join('');
  return `${h.bulletBlock('Tone guide', tg.principles)}${examples ? `<div class="slp-block">${examples}</div>` : ''}`;
}
