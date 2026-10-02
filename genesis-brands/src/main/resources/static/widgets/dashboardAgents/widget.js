const HEADERS = { 'X-Api-Key': 'genesis-ai-key' };

function esc(s) {
  if (!s) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/**
 * Resolves the one (engagementId, direction) slot this client's agent cards act on: the
 * most recent paid, finished engagement with a chosen direction. Live Dashboard always has
 * an owning Engagement to fall back on (unlike Playground), so each agent's view module
 * never needs a brief/questionnaire form — just this slot.
 */
async function resolveTarget() {
  const res = await fetch('/api/engagements/mine', { headers: HEADERS });
  if (!res.ok) throw new Error(String(res.status));
  const mine = await res.json();
  const target = mine.find(e =>
    e.status === 'DONE' && e.paymentStatus === 'PAID' && e.chosenDirection
  );
  return target ? { engagementId: target.id, direction: target.chosenDirection } : null;
}

function openAgentDialog(agentId, displayName, target, ctx) {
  const backdrop = document.createElement('div');
  backdrop.className = 'da-dialog-backdrop';
  backdrop.innerHTML = `
    <div class="da-dialog">
      <div class="da-dialog-header">
        <div class="da-dialog-title">${esc(displayName)}</div>
        <button class="da-dialog-close" type="button" aria-label="Close">&times;</button>
      </div>
      <div class="da-dialog-body"></div>
    </div>
  `;
  document.body.appendChild(backdrop);
  backdrop.classList.add('open');

  const close = () => backdrop.remove();
  backdrop.querySelector('.da-dialog-close').onclick = close;
  backdrop.onclick = (e) => { if (e.target === backdrop) close(); };

  import('/widgets/agent-loader.js').then(({ mountAgentLiveView }) => {
    mountAgentLiveView(agentId, backdrop.querySelector('.da-dialog-body'),
      { agentId, engagementId: target.engagementId, direction: target.direction },
      {
        simulate: ctx.simulate,
        onClose: close,
        onAssetGenerated: () => window.dispatchEvent(new CustomEvent('genesis:assets-updated')),
      });
  });
}

export async function mount(container, widget, ctx) {
  container.className = 'da-panel';
  container.innerHTML = `
    <div class="da-panel-label">Brand Agents</div>
    <div class="da-list"><div class="da-empty">Loading…</div></div>
  `;
  const listEl = container.querySelector('.da-list');

  try {
    const [agentsRes, target] = await Promise.all([
      fetch('/api/agents/live-view', { headers: HEADERS }),
      resolveTarget(),
    ]);
    if (!agentsRes.ok) throw new Error(String(agentsRes.status));
    const agents = await agentsRes.json();
    if (agents.length === 0) {
      listEl.innerHTML = '<div class="da-empty">No agents available yet.</div>';
      return;
    }

    listEl.innerHTML = '';
    agents.forEach(a => {
      const clickable = a.hasLiveView && !!target;
      const card = document.createElement('div');
      card.className = clickable ? 'da-card clickable' : 'da-card';
      card.innerHTML = `
        <div class="da-name">${esc(a.displayName)}</div>
        <div class="da-desc">${esc(a.description)}</div>
        <div class="da-badge${clickable ? ' live' : ''}">${clickable ? 'Manage' : 'Coming soon'}</div>
      `;
      if (clickable) {
        card.onclick = () => openAgentDialog(a.agentId, a.displayName, target, ctx || {});
      }
      listEl.appendChild(card);
    });
  } catch (err) {
    listEl.innerHTML = '<div class="da-empty">Could not load agents right now.</div>';
  }
}
