import { mountSlotPlayground } from '/agent-views/_shared/slotPlayground.js';

/** Brand Book agent's Playground view — assembles from upstream runs and can render the PDF. */
export function mount(container, agent, ctx) {
  return mountSlotPlayground(container, {
    agentId: 'brand-book',
    resultField: 'brandBook',
    sourceRuns: [
      { field: 'copy', agentId: 'copy', label: 'Copy' },
      { field: 'visualIdentity', agentId: 'visual-identity', label: 'Visual Identity' },
      { field: 'logo', agentId: 'logo', label: 'Logo' },
      { field: 'playbook', agentId: 'playbook', label: 'Playbook' },
    ],
    renderOutput: (o, h) => `
      ${h.block('Welcome note', o.welcomeNote)}
      ${h.block('How to use this guide', o.howToUseThisGuide)}
      ${h.block('Logo usage guidelines', o.logoUsageGuidelines)}
      ${h.bulletBlock('Logo don’ts', o.logoDonts)}
      ${h.block('Color usage guidelines', o.colorUsageGuidelines)}
      ${h.block('Typography usage guidelines', o.typographyUsageGuidelines)}
      ${h.block('Closing note', o.closingNote)}
      <div class="slp-block">
        <button class="slp-btn slp-btn-sm" data-action="download-pdf" data-output="${h.esc(JSON.stringify(o))}">Download PDF</button>
      </div>`,
    renderAudit: (o, h) => `
      ${h.block('Welcome note', o.welcomeNote)}
      ${h.block('Closing note', o.closingNote)}`,
    actions: { 'download-pdf': downloadPdf },
  }, ctx);
}

async function downloadPdf(btn, h) {
  const lastInput = h.getLastInput();
  if (!lastInput || !lastInput.brief) {
    alert('Run the agent (or reopen it from History) before downloading the PDF.');
    return;
  }
  const output = JSON.parse(btn.dataset.output);
  const label = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Rendering…';
  // the currently selected direction picks the template; the content stays the same
  const input = { ...lastInput, brief: { ...lastInput.brief, direction: h.getDirection() } };
  try {
    const res = await fetch('/api/agents/brand-book/render-pdf', {
      method: 'POST', headers: h.headers, body: JSON.stringify({ input, output }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');
    a.href = url;
    a.download = 'brand-book.pdf';
    a.click();
    URL.revokeObjectURL(url);
  } catch (err) {
    alert('PDF render failed: ' + (err.message || err));
  } finally {
    btn.disabled = false;
    btn.textContent = label;
  }
}
