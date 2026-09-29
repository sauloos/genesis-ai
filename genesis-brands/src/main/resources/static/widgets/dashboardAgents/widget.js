const HEADERS = { 'X-Api-Key': 'genesis-ai-key' };

function esc(s) {
  if (!s) return '';
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

export async function mount(container) {
  container.className = 'da-panel';
  container.innerHTML = `
    <div class="da-panel-label">Brand Agents</div>
    <div class="da-list"><div class="da-empty">Loading…</div></div>
  `;
  const listEl = container.querySelector('.da-list');

  try {
    const res = await fetch('/api/agents/live-view', { headers: HEADERS });
    if (!res.ok) throw new Error(String(res.status));
    const agents = await res.json();
    if (agents.length === 0) {
      listEl.innerHTML = '<div class="da-empty">No agents available yet.</div>';
      return;
    }
    listEl.innerHTML = agents.map(a => `
      <div class="da-card">
        <div class="da-name">${esc(a.displayName)}</div>
        <div class="da-desc">${esc(a.description)}</div>
        <div class="da-badge">Coming soon</div>
      </div>
    `).join('');
  } catch (err) {
    listEl.innerHTML = '<div class="da-empty">Could not load agents right now.</div>';
  }
}
