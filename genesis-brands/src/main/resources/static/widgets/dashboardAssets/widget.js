const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

const BOOK_ICON = '<svg class="dass-tile-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round"><path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/></svg>';
const LOGO_ICON = '<svg class="dass-tile-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round"><rect x="3.5" y="4.5" width="17" height="15" rx="2"/><circle cx="8.5" cy="9.5" r="1.5"/><path d="M20.5 15.5 15 10l-9 9"/></svg>';

function addLock(thumb) {
  if (thumb.querySelector('.dass-tile-lock')) return;
  const lock = document.createElement('div');
  lock.className = 'dass-tile-lock';
  lock.textContent = '🔒';
  thumb.appendChild(lock);
}

async function fetchBlobUrl(url) {
  try {
    const res = await fetch(url, { headers: HEADERS });
    if (!res.ok) return null;
    const blob = await res.blob();
    return URL.createObjectURL(blob);
  } catch (_) {
    return null;
  }
}

async function downloadPdf(engagementId, direction, tile, labelEl, originalLabel) {
  tile.disabled = true;
  labelEl.textContent = 'Generating…';
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
    tile.disabled = false;
    labelEl.textContent = originalLabel;
  }
}

async function downloadLogos(engagementId, direction, tile, labelEl, originalLabel) {
  tile.disabled = true;
  labelEl.textContent = 'Zipping…';
  try {
    const res = await fetch(`/api/engagements/${engagementId}/logos/${direction}`, { headers: HEADERS });
    if (!res.ok) throw new Error('Logos not available yet.');
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `logos-${direction.toLowerCase()}.zip`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (err) {
    alert(err.message || 'Logo download failed.');
  } finally {
    tile.disabled = false;
    labelEl.textContent = originalLabel;
  }
}

// `isCurrent` gates both the download action and the live preview-image fetch — the
// pdf/logos/preview endpoints always resolve against the engagement's CURRENT resultsJson,
// so a historical DirectionOutput snapshot (fetched from the versions endpoint) can only ever
// be browsed, never downloaded or thumbnailed through those routes.
function buildBookTile(summary, dir, downloadAllowed, isCurrent) {
  if (!dir.pdfBlobPath) return null;
  const label = dir.direction.charAt(0) + dir.direction.slice(1).toLowerCase();
  const tileLabel = `${label} Brand Book`;
  const canDownload = downloadAllowed && isCurrent;

  const tile = document.createElement('button');
  tile.type = 'button';
  tile.className = 'dass-tile';
  tile.disabled = !canDownload;

  const thumb = document.createElement('div');
  thumb.className = 'dass-tile-thumb';
  thumb.innerHTML = BOOK_ICON;
  tile.appendChild(thumb);

  if (isCurrent) {
    fetchBlobUrl(`/api/engagements/${summary.id}/preview/${dir.direction}`).then(url => {
      if (!url) return;
      const img = document.createElement('img');
      img.src = url;
      img.alt = `${tileLabel} preview`;
      thumb.innerHTML = '';
      thumb.appendChild(img);
      if (!downloadAllowed) addLock(thumb);
    });
  }
  if (!canDownload) addLock(thumb);

  const labelEl = document.createElement('div');
  labelEl.className = 'dass-tile-label';
  labelEl.textContent = tileLabel;
  tile.appendChild(labelEl);

  if (canDownload) {
    tile.onclick = () => downloadPdf(summary.id, dir.direction, tile, labelEl, tileLabel);
  } else {
    const sub = document.createElement('div');
    sub.className = 'dass-tile-sub';
    sub.textContent = isCurrent ? 'Locked' : 'Archived';
    tile.appendChild(sub);
  }

  return tile;
}

function buildLogoTile(summary, dir, downloadAllowed, isCurrent) {
  const logo = dir.logo;
  if (!logo) return null;
  const hasZip = !!dir.logoZipBlobPath;
  const canDownload = downloadAllowed && isCurrent && hasZip;
  const label = dir.direction.charAt(0) + dir.direction.slice(1).toLowerCase();
  const tileLabel = `${label} Logo`;

  const tile = document.createElement('button');
  tile.type = 'button';
  tile.className = 'dass-tile';
  tile.disabled = !canDownload;

  const thumb = document.createElement('div');
  thumb.className = 'dass-tile-thumb dass-logo-thumb';
  if (logo.method === 'SVG_CONCEPT' && logo.svgMarkup) {
    thumb.innerHTML = logo.svgMarkup;
  } else if (logo.imageUrl) {
    const img = document.createElement('img');
    img.src = logo.imageUrl;
    img.alt = `${tileLabel} preview`;
    thumb.appendChild(img);
  } else {
    thumb.innerHTML = LOGO_ICON;
  }
  tile.appendChild(thumb);

  if (!downloadAllowed && isCurrent) addLock(thumb);

  const labelEl = document.createElement('div');
  labelEl.className = 'dass-tile-label';
  labelEl.textContent = tileLabel;
  tile.appendChild(labelEl);

  if (canDownload) {
    tile.onclick = () => downloadLogos(summary.id, dir.direction, tile, labelEl, tileLabel);
  } else {
    const sub = document.createElement('div');
    sub.className = 'dass-tile-sub';
    sub.textContent = !isCurrent ? 'Archived' : (!downloadAllowed ? 'Locked' : 'Not ready');
    tile.appendChild(sub);
  }

  return tile;
}

function renderDirectionTiles(gridEl, summary, dir, downloadAllowed, isCurrent) {
  gridEl.innerHTML = '';
  const bookTile = buildBookTile(summary, dir, downloadAllowed, isCurrent);
  if (bookTile) gridEl.appendChild(bookTile);
  const logoTile = buildLogoTile(summary, dir, downloadAllowed, isCurrent);
  if (logoTile) gridEl.appendChild(logoTile);
}

async function fetchVersions(engagementId, direction) {
  try {
    const res = await fetch(`/api/engagements/${engagementId}/directions/${direction}/versions`, { headers: HEADERS });
    if (!res.ok) return [];
    return await res.json();
  } catch (_) {
    return [];
  }
}

async function fetchVersion(engagementId, direction, versionNumber) {
  const res = await fetch(`/api/engagements/${engagementId}/directions/${direction}/versions/${versionNumber}`, { headers: HEADERS });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function buildDirectionBlock(summary, dir, downloadAllowed, versions) {
  const label = dir.direction.charAt(0) + dir.direction.slice(1).toLowerCase();

  const block = document.createElement('div');
  block.className = 'dass-direction-block';

  const header = document.createElement('div');
  header.className = 'dass-direction-header';

  const title = document.createElement('div');
  title.className = 'dass-direction-title';
  title.textContent = label;
  header.appendChild(title);

  let select = null;
  if (versions.length > 0) {
    select = document.createElement('select');
    select.className = 'dass-version-select';
    const currentOpt = document.createElement('option');
    currentOpt.value = 'current';
    currentOpt.textContent = 'Current';
    select.appendChild(currentOpt);
    versions.forEach(v => {
      const opt = document.createElement('option');
      opt.value = String(v.versionNumber);
      const when = v.createdAt ? new Date(v.createdAt).toLocaleDateString() : '';
      opt.textContent = `Version ${v.versionNumber}${when ? ` — ${when}` : ''}`;
      select.appendChild(opt);
    });
    header.appendChild(select);
  }

  block.appendChild(header);

  const banner = document.createElement('div');
  banner.className = 'dass-version-banner';
  banner.hidden = true;
  block.appendChild(banner);

  const grid = document.createElement('div');
  grid.className = 'dass-tile-grid';
  block.appendChild(grid);

  renderDirectionTiles(grid, summary, dir, downloadAllowed, true);

  if (select) {
    select.onchange = async () => {
      if (select.value === 'current') {
        banner.hidden = true;
        renderDirectionTiles(grid, summary, dir, downloadAllowed, true);
        return;
      }
      select.disabled = true;
      try {
        const snapshot = await fetchVersion(summary.id, dir.direction, Number(select.value));
        banner.hidden = false;
        banner.textContent = `Viewing version ${select.value} — read-only, downloads are only available for Current.`;
        renderDirectionTiles(grid, summary, snapshot, downloadAllowed, false);
      } catch (_) {
        banner.hidden = false;
        banner.textContent = 'Could not load that version.';
        grid.innerHTML = '';
      } finally {
        select.disabled = false;
      }
    };
  }

  return block;
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

  const relevantDirs = dirs.filter(dir => dir.pdfBlobPath || dir.logo);
  if (!relevantDirs.length) return null;

  const versionsByDirection = await Promise.all(
    relevantDirs.map(dir => fetchVersions(summary.id, dir.direction))
  );

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

  relevantDirs.forEach((dir, i) => {
    block.appendChild(buildDirectionBlock(summary, dir, downloadAllowed, versionsByDirection[i]));
  });

  return block;
}

async function render(container) {
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
    const rendered = blocks.filter(Boolean);
    if (rendered.length === 0) {
      listEl.innerHTML = '<div class="dass-empty">No brand assets yet. Complete a brand journey to see your downloads here.</div>';
      return;
    }
    rendered.forEach(b => listEl.appendChild(b));
  } catch (err) {
    listEl.innerHTML = '<div class="dass-empty">Could not load your assets right now.</div>';
  }
}

export async function mount(container) {
  container.className = 'dass-panel';
  container.innerHTML = `
    <div class="dass-panel-label">Your Assets</div>
    <div class="dass-list"><div class="dass-empty">Loading…</div></div>
  `;
  await render(container);

  // A specialist agent's Live Dashboard view (dashboardAgents widget) just approved a draft
  // for one of this engagement's assets — re-render so PDF/Logo ZIP tiles and the version
  // selects pick up the newly archived version plus the updated current content.
  window.addEventListener('genesis:assets-updated', () => render(container));
}
