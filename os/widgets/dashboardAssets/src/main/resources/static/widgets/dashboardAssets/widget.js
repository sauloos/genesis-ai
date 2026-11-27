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

// Preview images by URL, so a re-render (the in-progress poll re-renders every 10s) reuses
// the covers it already has instead of asking the server to render them again. Cleared
// whenever the assets may have changed.
const previewCache = new Map();

function clearPreviewCache() {
  previewCache.forEach(p => p.then(url => { if (url) URL.revokeObjectURL(url); }));
  previewCache.clear();
}

function fetchBlobUrl(url) {
  if (!previewCache.has(url)) {
    const pending = fetch(url, { headers: HEADERS })
      .then(res => (res.ok ? res.blob() : null))
      .then(blob => (blob ? URL.createObjectURL(blob) : null))
      .catch(() => null)
      .then(objectUrl => {
        // A failed preview is retried on the next render rather than remembered.
        if (!objectUrl && previewCache.get(url) === pending) previewCache.delete(url);
        return objectUrl;
      });
    previewCache.set(url, pending);
  }
  return previewCache.get(url);
}

// The browser downloads the file itself, inside the click: it shows its own progress and
// saves the file when the transfer ends. Fetching the bytes in script and saving a blob
// afterwards fails silently once a large file takes more than a few seconds, because by
// then the browser no longer treats the save as part of the click. So a downloadUrl must be
// a plain GET that the client's session cookie authorises (no custom headers), answering
// with Content-Disposition: attachment (see ClientWorkItem.Asset).
function downloadAsset(downloadUrl, filename, tile, labelEl, originalLabel, busyLabel) {
  const a = document.createElement('a');
  a.href = downloadUrl;
  a.download = filename;
  a.style.display = 'none';
  // Some browsers ignore a click on a detached link.
  document.body.appendChild(a);
  a.click();
  a.remove();
  // Acknowledge the click until the browser's own download indicator takes over.
  tile.disabled = true;
  labelEl.textContent = busyLabel;
  setTimeout(() => {
    tile.disabled = false;
    labelEl.textContent = originalLabel;
  }, 4000);
}


// Logos arrive with generous empty margins. Crop to the visible mark (transparent pixels, or
// pixels matching the corner colour on an opaque image) so the tile can take the logo's own shape.
// Any failure (tainted canvas, decode error) leaves the original image in place.
function trimmedLogo(src) {
  return new Promise(resolve => {
    const im = new Image();
    im.onload = () => {
      try {
        const w = im.naturalWidth, h = im.naturalHeight;
        if (!w || !h) return resolve(null);
        const scale = Math.min(1, 512 / Math.max(w, h));
        const cw = Math.max(1, Math.round(w * scale)), ch = Math.max(1, Math.round(h * scale));
        const c = document.createElement('canvas');
        c.width = cw; c.height = ch;
        const g = c.getContext('2d', { willReadFrequently: true });
        g.drawImage(im, 0, 0, cw, ch);
        const px = g.getImageData(0, 0, cw, ch).data;
        const at = (x, y) => (y * cw + x) * 4;
        const k = at(0, 0);
        const opaqueBg = px[k + 3] > 240;
        const bg = [px[k], px[k + 1], px[k + 2]];
        const isInk = i => opaqueBg
          ? Math.abs(px[i] - bg[0]) + Math.abs(px[i + 1] - bg[1]) + Math.abs(px[i + 2] - bg[2]) > 40
          : px[i + 3] > 16;
        let x0 = cw, y0 = ch, x1 = -1, y1 = -1;
        for (let y = 0; y < ch; y++) for (let x = 0; x < cw; x++) {
          if (isInk(at(x, y))) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
        }
        if (x1 < 0) return resolve(null);
        const pad = Math.round(Math.max(x1 - x0, y1 - y0) * 0.04);
        x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad);
        x1 = Math.min(cw - 1, x1 + pad); y1 = Math.min(ch - 1, y1 + pad);
        const tw = x1 - x0 + 1, th = y1 - y0 + 1;
        const out = document.createElement('canvas');
        out.width = tw; out.height = th;
        out.getContext('2d').drawImage(c, x0, y0, tw, th, 0, 0, tw, th);
        out.toBlob(b => resolve(b ? { url: URL.createObjectURL(b), aspect: tw / th } : null), 'image/png');
      } catch (e) { resolve(null); }
    };
    im.onerror = () => resolve(null);
    im.src = src;
  });
}

// Shape a tile after its picture: a landscape cover or a wide wordmark gets a landscape sheet
// spanning two grid columns; portrait and square stay one column wide.
function shapeTile(tile, doc, aspect, wideFrom) {
  const a = Math.min(3, Math.max(0.5, aspect));
  doc.style.setProperty('--dass-aspect', a.toFixed(3));
  if (a >= wideFrom) tile.classList.add('dass-tile-wide');
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
  // A document reads as a sheet of paper with a turned-down corner; a logo package stays a
  // square. The wrapper carries the shadow because clip-path would cut it off the thumb itself.
  const doc = document.createElement('div');
  doc.className = asset.kind === 'image' ? 'dass-doc dass-doc-square' : 'dass-doc';
  doc.appendChild(thumb);
  if (canDownload) {
    const cue = document.createElement('div');
    cue.className = 'dass-doc-cue';
    cue.textContent = 'Download';
    doc.appendChild(cue);
  }
  tile.appendChild(doc);

  if (asset.kind === 'image') {
    let src = asset.previewUrl;
    if (asset.inlineSvg) {
      thumb.innerHTML = asset.inlineSvg;
      src = URL.createObjectURL(new Blob([asset.inlineSvg], { type: 'image/svg+xml' }));
    } else if (asset.previewUrl) {
      const img = document.createElement('img');
      img.alt = `${asset.label} preview`;
      // The stored image can be gone (e.g. written before assets moved to Blob storage):
      // show the placeholder icon rather than the browser's broken-image glyph.
      img.onerror = () => {
        img.remove();
        thumb.insertAdjacentHTML('afterbegin', LOGO_ICON);
        thumb.title = 'Preview unavailable';
      };
      img.src = asset.previewUrl;
      thumb.innerHTML = '';
      thumb.appendChild(img);
    }
    if (src) {
      trimmedLogo(src).then(trim => {
        if (asset.inlineSvg) URL.revokeObjectURL(src);
        if (!trim) return;
        const img = document.createElement('img');
        img.alt = `${asset.label} preview`;
        img.src = trim.url;
        thumb.innerHTML = '';
        thumb.appendChild(img);
        shapeTile(tile, doc, trim.aspect, 1.5);
        if (isCurrent && !unlocked) addLock(thumb);
      });
    }
    if (isCurrent && !unlocked) addLock(thumb);
  } else if (asset.previewUrl) {
    fetchBlobUrl(asset.previewUrl).then(url => {
      if (!url) return;
      const img = document.createElement('img');
      img.alt = `${asset.label} preview`;
      img.onload = () => shapeTile(tile, doc, img.naturalWidth / img.naturalHeight, 1.2);
      img.src = url;
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
    const busyLabel = 'Downloading…';
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
  const variants = item.variants || [];
  if (!variants.length && !item.statusNote) return null;

  const block = document.createElement('div');
  block.className = 'dass-engagement-block';

  const title = document.createElement('div');
  title.className = 'dass-engagement-title';
  title.textContent = item.title || 'Your brand';
  block.appendChild(title);

  const sub = document.createElement('div');
  sub.className = 'dass-engagement-sub';
  sub.textContent = item.statusNote
    || (item.unlocked ? 'Unlocked' : 'Payment required to unlock downloads');
  block.appendChild(sub);

  variants.forEach(variant => {
    block.appendChild(buildDirectionBlock(item.id, variant, item.unlocked));
  });

  return block;
}

const IN_PROGRESS_POLL_MS = 10000;

async function render(container, fromPoll = false) {
  const listEl = container.querySelector('.dass-list');
  clearTimeout(container._dassPoll);
  if (!fromPoll) clearPreviewCache();

  try {
    const items = await fetch('/api/client/workspace', { headers: HEADERS }).then(r => {
      if (!r.ok) throw new Error(String(r.status));
      return r.json();
    });
    // Something finished since the last poll: its covers may be new.
    if (fromPoll && !items.some(i => i.inProgress)) clearPreviewCache();
    listEl.innerHTML = '';
    const blocks = items.map(renderWorkItemBlock).filter(Boolean);
    if (blocks.length === 0) {
      listEl.innerHTML = '<div class="dass-empty">No brand assets yet. Complete a brand journey to see your downloads here.</div>';
      return;
    }
    blocks.forEach(b => listEl.appendChild(b));
    // Something is still being produced — check again until it's ready (or the visitor leaves).
    if (items.some(i => i.inProgress)) {
      container._dassPoll = setTimeout(() => { if (container.isConnected) render(container, true); }, IN_PROGRESS_POLL_MS);
    }
  } catch (err) {
    listEl.innerHTML = '<div class="dass-empty">Could not load your assets right now.</div>';
  }
}

export async function mount(container) {
  container.classList.add('dass-panel');
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
