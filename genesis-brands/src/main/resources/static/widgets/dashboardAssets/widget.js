const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

async function downloadPdf(engagementId, direction, btn) {
  if (btn) { btn.disabled = true; btn.textContent = 'Generating…'; }
  try {
    const res = await fetch(`/api/engagements/${engagementId}/pdf/${direction}`, { headers: HEADERS });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `brand-book-${direction.toLowerCase()}.pdf`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (err) {
    alert('PDF download failed: ' + (err.message || err));
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = '↓ PDF'; }
  }
}

async function downloadLogos(engagementId, direction) {
  const res = await fetch(`/api/engagements/${engagementId}/logos/${direction}`, { headers: HEADERS });
  if (!res.ok) { alert('Logos not available yet.'); return; }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `logos-${direction.toLowerCase()}.zip`;
  a.click();
  URL.revokeObjectURL(url);
}

async function renderEngagementBlock(summary) {
  let detail;
  try {
    detail = await fetch(`/api/engagements/${summary.id}`, { headers: HEADERS }).then(r => r.json());
  } catch (_) {
    return null;
  }
  if (!detail.results) return null;

  const dirs = detail.results.directions || [];
  const brandName = dirs[0]?.brief?.brand?.name || 'Your brand';
  const downloadAllowed = detail.downloadAllowed;

  const block = document.createElement('div');
  block.className = 'dass-engagement-block';

  const title = document.createElement('div');
  title.className = 'dass-engagement-title';
  title.textContent = brandName;
  block.appendChild(title);

  const sub = document.createElement('div');
  sub.className = 'dass-engagement-sub';
  sub.textContent = downloadAllowed ? 'Unlocked' : 'Payment required to unlock downloads';
  block.appendChild(sub);

  dirs.forEach(dir => {
    const label = dir.direction.charAt(0) + dir.direction.slice(1).toLowerCase();
    const hasLogo = !!dir.logoZipBlobPath;

    const row = document.createElement('div');
    row.className = 'dass-asset-row';

    const labelEl = document.createElement('div');
    labelEl.className = 'dass-asset-row-label';
    labelEl.textContent = `${label} — Brand Book${hasLogo ? ' + Logos' : ''}`;
    row.appendChild(labelEl);

    const actions = document.createElement('div');
    actions.className = 'dass-asset-row-actions';
    if (downloadAllowed) {
      const pdfBtn = document.createElement('button');
      pdfBtn.className = 'dass-asset-btn';
      pdfBtn.textContent = '↓ PDF';
      pdfBtn.onclick = () => downloadPdf(summary.id, dir.direction, pdfBtn);
      actions.appendChild(pdfBtn);
      if (hasLogo) {
        const logoBtn = document.createElement('button');
        logoBtn.className = 'dass-asset-btn';
        logoBtn.textContent = '↓ Logos';
        logoBtn.onclick = () => downloadLogos(summary.id, dir.direction);
        actions.appendChild(logoBtn);
      }
    } else {
      const locked = document.createElement('span');
      locked.className = 'dass-locked-note';
      locked.textContent = 'Locked';
      actions.appendChild(locked);
    }
    row.appendChild(actions);

    block.appendChild(row);
  });

  return block;
}

export async function mount(container) {
  container.className = 'dass-panel';
  container.innerHTML = `
    <div class="dass-panel-label">Your Assets</div>
    <div class="dass-list"><div class="dass-empty">Loading…</div></div>
  `;
  const listEl = container.querySelector('.dass-list');

  try {
    const mine = await fetch('/api/engagements/mine', { headers: HEADERS }).then(r => {
      if (!r.ok) throw new Error(String(r.status));
      return r.json();
    });
    const done = mine.filter(e => e.status === 'DONE');
    if (done.length === 0) {
      listEl.innerHTML = '<div class="dass-empty">No brand assets yet. Complete a brand journey to see your downloads here.</div>';
      return;
    }
    const blocks = await Promise.all(done.map(renderEngagementBlock));
    listEl.innerHTML = '';
    blocks.filter(Boolean).forEach(b => listEl.appendChild(b));
  } catch (err) {
    listEl.innerHTML = '<div class="dass-empty">Could not load your assets right now.</div>';
  }
}
