/**
 * Document preview viewer (core). openDocumentPreview() fetches a document's page manifest and
 * shows it in PhotoSwipe. Only watermarked page images ever reach the browser; pages the server
 * withholds are summarised in text so the viewer knows what it is not seeing. Used by the
 * documentPreview widget and by any widget that offers "Look inside".
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };
const BASE = '/widgets/documentPreview/';

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

let assets = null;
function loadAssets() {
  if (!assets) {
    for (const href of [BASE + 'vendor/photoswipe/photoswipe.css', BASE + 'viewer.css']) {
      const link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = href;
      document.head.appendChild(link);
    }
    assets = import(BASE + 'vendor/photoswipe/photoswipe.esm.min.js').then(m => m.default);
  }
  return assets;
}

export async function fetchManifest(workItemId, variantKey) {
  const res = await fetch(`/api/client/previews/${encodeURIComponent(workItemId)}/${encodeURIComponent(variantKey)}/manifest`,
    { headers: HEADERS, cache: 'no-store' });
  if (!res.ok) throw new Error('preview unavailable (' + res.status + ')');
  return res.json();
}

// Consecutive withheld pages become one slide, so a viewer sees "pages 4-9 are not shown" once.
export function buildSlides(pages) {
  const slides = [];
  let run = [];
  const flush = () => {
    if (!run.length) return;
    slides.push({ kind: 'skip', pages: run, first: run[0].index, last: run[run.length - 1].index });
    run = [];
  };
  for (const p of pages) {
    if (p.access === 'WITHHOLD' || !p.url) { run.push(p); continue; }
    flush();
    slides.push({ kind: 'page', page: p });
  }
  flush();
  return slides;
}

const range = (a, b) => (a === b ? `Page ${a + 1}` : `Pages ${a + 1} to ${b + 1}`);

function skipElement(slide) {
  const box = el('div', 'dp-skip');
  box.appendChild(el('p', 'dp-skip-kicker', 'Not part of this preview'));
  box.appendChild(el('h3', 'dp-skip-title', range(slide.first, slide.last)));
  const list = el('ul', 'dp-skip-list');
  for (const p of slide.pages) {
    const li = el('li', 'dp-skip-item');
    li.appendChild(el('strong', undefined, p.chapter || 'Page'));
    const detail = (p.description || p.label || '').trim();
    if (detail) li.appendChild(el('span', undefined, ' ' + detail));
    list.appendChild(li);
  }
  box.appendChild(list);
  box.appendChild(el('p', 'dp-skip-hint', 'Swipe or press the arrow to continue.'));
  return box;
}

function placeholderElement(page) {
  const box = el('div', 'dp-skip dp-sample');
  box.appendChild(el('p', 'dp-skip-kicker', 'Sample page'));
  box.appendChild(el('h3', 'dp-skip-title', page.chapter || ('Page ' + (page.index + 1))));
  box.appendChild(el('p', 'dp-skip-hint', page.label || 'A real, watermarked page appears here.'));
  return box;
}

/**
 * @param {object} o
 * @param {string} [o.workItemId] @param {string} [o.variantKey]
 * @param {object} [o.manifest]   pre-built manifest (Simulate samples); pages without a url are placeholders
 * @param {string} [o.title]
 * @param {string} [o.chooseLabel] @param {Function} [o.onChoose]  adds a choose button inside the viewer
 * @returns {Promise<object>} the PhotoSwipe instance; rejects when the manifest cannot be loaded
 */
export async function openDocumentPreview(o) {
  const manifest = o.manifest || await fetchManifest(o.workItemId, o.variantKey);
  const PhotoSwipe = await loadAssets();
  const slides = buildSlides(manifest.pages || []);
  if (!slides.length) throw new Error('nothing to show');

  const dataSource = slides.map(s => {
    if (s.kind === 'skip') return { type: 'html', html: skipElement(s).outerHTML };
    if (!s.page.url) return { type: 'html', html: placeholderElement(s.page).outerHTML };
    return { src: s.page.url, width: 1200, height: 1697, alt: `${s.page.chapter || 'Page'} (page ${s.page.index + 1})` };
  });

  const pswp = new PhotoSwipe({
    dataSource,
    bgOpacity: 0.96,
    wheelToZoom: false,
    closeOnVerticalDrag: true,
    arrowKeys: true,
    counter: true,
    showHideAnimationType: 'fade',
    initialZoomLevel: 'fit',
    secondaryZoomLevel: 1.5,
    maxZoomLevel: 2,
    errorMsg: 'This page could not be loaded. Close the preview and open it again.',
  });

  pswp.on('uiRegister', () => {
    pswp.ui.registerElement({
      name: 'dp-caption', order: 9, isButton: false, appendTo: 'root',
      onInit: (node) => {
        node.classList.add('dp-caption');
        const title = el('span', 'dp-caption-title');
        const total = el('span', 'dp-caption-total');
        node.append(title, total);
        const show = () => {
          const s = slides[pswp.currIndex];
          if (s.kind === 'skip') { title.textContent = range(s.first, s.last) + ' · not shown'; }
          else title.textContent = `${s.page.chapter || 'Page'} · page ${s.page.index + 1}`;
          total.textContent = manifest.title ? manifest.title + (manifest.variantLabel ? ' · ' + manifest.variantLabel : '') : '';
        };
        pswp.on('change', show);
        show();
      },
    });
    if (typeof o.onChoose === 'function') {
      pswp.ui.registerElement({
        name: 'dp-choose', order: 8, isButton: false, appendTo: 'root',
        onInit: (node) => {
          node.classList.add('dp-choose');
          const btn = el('button', 'dp-choose-btn', o.chooseLabel || 'Choose this');
          btn.type = 'button';
          btn.onclick = () => { pswp.close(); o.onChoose(); };
          node.appendChild(btn);
        },
      });
    }
  });

  // Deterrent only (the watermark is the control): no context menu or drag-save on pages.
  pswp.on('contentAppend', () => {});
  pswp.on('openingAnimationStart', () => {
    pswp.element?.addEventListener('contextmenu', e => e.preventDefault());
    pswp.element?.addEventListener('dragstart', e => e.preventDefault());
  });

  // Signed page links are short-lived: refresh the not-yet-loaded ones while the viewer stays open.
  let timer = 0;
  if (!o.manifest && manifest.expiresInSeconds > 30 && o.workItemId) {
    const refresh = async () => {
      try {
        const fresh = await fetchManifest(o.workItemId, o.variantKey);
        const urls = new Map((fresh.pages || []).map(p => [p.index, p.url]));
        slides.forEach((s, i) => { if (s.kind === 'page' && urls.get(s.page.index)) dataSource[i].src = urls.get(s.page.index); });
      } catch (e) { /* keep what is loaded; a stale page shows the viewer's error message */ }
      timer = setTimeout(refresh, manifest.expiresInSeconds * 800);
    };
    timer = setTimeout(refresh, manifest.expiresInSeconds * 800);
  }
  pswp.on('destroy', () => clearTimeout(timer));

  pswp.init();
  return pswp;
}
