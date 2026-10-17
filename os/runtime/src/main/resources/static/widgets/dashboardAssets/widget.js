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

async function downloadAsset(downloadUrl, filename, tile, labelEl, originalLabel, busyLabel) {
  tile.disabled = true;
  labelEl.textContent = busyLabel;
  try {
    const res = await fetch(downloadUrl, { headers: HEADERS });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  } catch (err) {
    alert('Download failed: ' + (err.message || err));
  } finally {
    tile.disabled = false;
    labelEl.textContent = originalLabel;
  }
}

// `isCurrent` gates both the download action and the live preview-image fetch — a
// historical variant snapshot (fetched from the versions endpoint) can only ever be
// browsed, never downloaded or thumbnailed, and the generic ClientWorkItem.Asset DTO
// already reflects that (downloadUrl/previewUrl are null for archived variants).
function buildTile(asset, unlocked, isCurrent) {
  const canDownload = unlocked && !!asset.downloadUrl;

  const tile = document.createElement('button');
  tile.type = 'button';
  tile.className = 'dass-tile';
  tile.disabled = !canDownload;

  const thumb = document.createElement('div');
  thumb.className = asset.kind === 'image' ? 'dass-tile-thumb dass-logo-thumb' : 'dass-tile-thumb';
  thumb.innerHTML = asset.kind === 'image' ? LOGO_ICON : BOOK_ICON;
  tile.appendChild(thumb);

  if (asset.kind === 'image') {
    if (asset.inlineSvg) {
      thumb.innerHTML = asset.inlineSvg;
    } else if (asset.previewUrl) {
      const img = document.createElement('img');
      img.src = asset.previewUrl;
      img.alt = `${asset.label} preview`;
      thumb.innerHTML = '';
      thumb.appendChild(img);
    }
    if (isCurrent && !unlocked) addLock(thumb);
  } else if (asset.previewUrl) {
    fetchBlobUrl(asset.previewUrl).then(url => {
      if (!url) return;
      const img = document.createElement('img');
      img.src = url;
      img.alt = `${asset.label} preview`;
      thumb.innerHTML = '';
      thumb.appendChild(img);
      if (!unlocked) addLock(thumb);
    });
  }
  if (!canDownload) addLock(thumb);

  const labelEl = document.createElement('div');
  labelEl.className = 'dass-tile-label';
  labelEl.textContent = asset.label;
  tile.appendChild(labelEl);

  if (canDownload) {
    const ext = asset.kind === 'image' ? 'zip' : 'pdf';
    const filename = `${asset.label.toLowerCase().replace(/\s+/g, '-')}.${ext}`;
    const busyLabel = asset.kind === 'image' ? 'Zipping…' : 'Generating…';
    tile.onclick = () => downloadAsset(asset.downloadUrl, filename, tile, labelEl, asset.label, busyLabel);
  } else {
    const sub = document.createElement('div');
    sub.className = 'dass-tile-sub';
    sub.textContent = !isCurrent ? 'Archived' : (!unlocked ? 'Locked' : 'Not ready');
    tile.appendChild(sub);
  }

  return tile;
}

function renderVariantTiles(gridEl, variant, unlocked, isCurrent) {
  gridEl.innerHTML = '';
  (variant.assets || []).forEach(asset => gridEl.appendChild(buildTile(asset, unlocked, isCurrent)));
}

async function fetchVersion(workItemId, variantKey, versionNumber) {
  const res = await fetch(`/api/client/workspace/${workItemId}/variants/${variantKey}/versions/${versionNumber}`, { headers: HEADERS });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function buildDirectionBlock(workItemId, variant, unlocked) {
  const label = variant.key.charAt(0) + variant.key.slice(1).toLowerCase();
  const versions = variant.versions || [];

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

  renderVariantTiles(grid, variant, unlocked, true);

  if (select) {
    select.onchange = async () => {
      if (select.value === 'current') {
        banner.hidden = true;
        renderVariantTiles(grid, variant, unlocked, true);
        return;
      }
      select.disabled = true;
      try {
        const snapshot = await fetchVersion(workItemId, variant.key, Number(select.value));
        banner.hidden = false;
        banner.textContent = `Viewing version ${select.value} — read-only, downloads are only available for Current.`;
        renderVariantTiles(grid, snapshot, unlocked, false);
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

function renderWorkItemBlock(item) {
  if (!item.variants || !item.variants.length) return null;

  const block = document.createElement('div');
  block.className = 'dass-engagement-block';

  const title = document.createElement('div');
  title.className = 'dass-engagement-title';
  title.textContent = item.title || 'Your brand';
  block.appendChild(title);

  const sub = document.createElement('div');
  sub.className = 'dass-engagement-sub';
  sub.textContent = item.unlocked ? 'Unlocked' : 'Payment required to unlock downloads';
  block.appendChild(sub);

  item.variants.forEach(variant => {
    block.appendChild(buildDirectionBlock(item.id, variant, item.unlocked));
  });

  return block;
}

async function render(container) {
  const listEl = container.querySelector('.dass-list');

  try {
    const items = await fetch('/api/client/workspace', { headers: HEADERS }).then(r => {
      if (!r.ok) throw new Error(String(r.status));
      return r.json();
    });
    listEl.innerHTML = '';
    const blocks = items.map(renderWorkItemBlock).filter(Boolean);
    if (blocks.length === 0) {
      listEl.innerHTML = '<div class="dass-empty">No brand assets yet. Complete a brand journey to see your downloads here.</div>';
      return;
    }
    blocks.forEach(b => listEl.appendChild(b));
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
