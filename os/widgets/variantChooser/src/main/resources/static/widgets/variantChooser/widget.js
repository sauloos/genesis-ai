/**
 * Variant chooser (core) — compares the variants of one client work item and lets the visitor
 * choose one. Data comes from the generic client workspace; the choice goes through the core
 * choose endpoint, so nothing here knows what a variant is for a given tenant.
 *
 * Layout: three columns with aligned rows on wide screens; a swipeable carousel with a segmented
 * control below 1000px, plus a sticky action bar on phones.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const text = v => (typeof v === 'string' ? v.trim() : '');

// The tagline is set in the variant's own headline font. The name comes from model output, so
// only plain family names are accepted.
function fontOf(d) {
  const name = text(d.headlineFont);
  return /^[A-Za-z0-9 ]{2,40}$/.test(name) ? name : '';
}

function loadFonts(names) {
  const unique = [...new Set(names.filter(Boolean))];
  if (!unique.length) return;
  const href = 'https://fonts.googleapis.com/css2?'
    + unique.map(n => 'family=' + encodeURIComponent(n).replace(/%20/g, '+')).join('&') + '&display=swap';
  if ([...document.head.querySelectorAll('link[rel=stylesheet]')].some(l => l.href === href)) return;
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = href;
  document.head.appendChild(link);
}

function bullets(label, items) {
  const list = (Array.isArray(items) ? items : []).map(text).filter(Boolean).slice(0, 3);
  const row = el('div', 'vc-row vc-fact');
  if (!list.length) return row;
  const ul = el('ul', 'vc-bullets');
  list.forEach(i => ul.appendChild(el('li', '', i)));
  row.append(el('div', 'vc-fact-label', label), ul);
  return row;
}

function fact(label, value) {
  const row = el('div', 'vc-row vc-fact');
  if (!value) return row;
  row.append(el('div', 'vc-fact-label', label), el('div', 'vc-fact-text', value));
  return row;
}

export function mount(container, widget, ctx) {
  const config = widget.config || {};
  const heading = config.heading || 'Your brand directions are ready';
  const intro = config.intro || 'Three ways to be you. Compare them, look inside, then choose the one that feels right.';
  ctx.requireCompletion?.();

  const status = (message, isError) => {
    container.innerHTML = '';
    container.append(el('h2', 'vc-heading', heading), el(isError ? 'div' : 'p', isError ? 'vc-error' : 'vc-status', message));
  };

  const choose = async (item, variant, controls, errorEl, dialog) => {
    controls.forEach(b => { b.disabled = true; });
    errorEl.textContent = '';
    try {
      const res = await fetch(`/api/client/workspace/${encodeURIComponent(item.id)}/variants/${encodeURIComponent(variant.key)}/choose`,
        { method: 'POST', headers: HEADERS });
      if (!res.ok) throw new Error('choose failed');
      if (dialog && dialog.open) dialog.close();
      ctx.complete?.();
      ctx.onOutcome?.('next');
    } catch (e) {
      controls.forEach(b => { b.disabled = false; });
      errorEl.textContent = 'We could not save your choice. Check your connection and try again.';
    }
  };

  // One native <dialog> does both jobs: look inside, and confirm. Focus is trapped and Escape closes it.
  const openDialog = (item, variant, mode, controls, pageError) => {
    const d = variant.decision || {};
    const name = d.label || variant.key;
    const dialog = el('dialog', 'vc-dialog');
    const titleId = `vc-title-${Math.random().toString(36).slice(2, 8)}`;
    dialog.setAttribute('aria-labelledby', titleId);
    const head = el('div', 'vc-dialog-head');
    const title = el('h3', 'vc-dialog-title', mode === 'confirm' ? `Choose ${name}?` : `${name} · look inside`);
    title.id = titleId;
    const closeBtn = el('button', 'vc-btn vc-btn-quiet', 'Close');
    closeBtn.type = 'button';
    closeBtn.onclick = () => dialog.close();
    head.append(title, closeBtn);
    dialog.appendChild(head);

    if (mode === 'confirm') {
      dialog.appendChild(el('p', 'vc-dialog-text',
        `Everything we build next, from your brand book to your assets, follows the ${name} direction. ` +
        'You can still look at the others first.'));
    } else {
      if (d.previewUrl) {
        const img = el('img', 'vc-dialog-img');
        img.src = d.previewUrl;
        img.alt = `${name} preview`;
        dialog.appendChild(img);
      }
      if (d.summary) dialog.appendChild(el('p', 'vc-dialog-text', d.summary));
    }

    const dialogError = el('div', 'vc-error vc-dialog-error');
    dialogError.setAttribute('role', 'alert');
    const actions = el('div', 'vc-dialog-actions');
    const back = el('button', 'vc-btn', mode === 'confirm' ? 'Keep looking' : 'Back to directions');
    back.type = 'button';
    back.onclick = () => dialog.close();
    const go = el('button', 'vc-btn vc-btn-primary', mode === 'confirm' ? `Yes, choose ${name}` : `Choose ${name}`);
    go.type = 'button';
    go.onclick = () => {
      if (mode === 'confirm') choose(item, variant, [go, back, ...controls], dialogError, dialog);
      else { dialog.close(); openDialog(item, variant, 'confirm', controls, pageError); }
    };
    actions.append(back, go);
    dialog.append(dialogError, actions);
    dialog.addEventListener('close', () => dialog.remove());
    container.appendChild(dialog);
    dialog.showModal();
  };

  const render = (item) => {
    const variants = item.variants.filter(v => v.decision);
    container.innerHTML = '';
    loadFonts(variants.map(v => fontOf(v.decision)));

    const pageError = el('div', 'vc-error vc-page-error');
    pageError.setAttribute('role', 'alert');
    const controls = [];

    // A spectrum that teaches the names without a glossary.
    const ribbon = el('div', 'vc-ribbon');
    ribbon.setAttribute('role', 'img');
    ribbon.setAttribute('aria-label', 'From closest to your brand today to boldest: ' + variants.map(v => v.decision.label || v.key).join(', then '));
    const track = el('div', 'vc-ribbon-track');
    variants.forEach(v => track.appendChild(el('span', 'vc-ribbon-stop', v.decision.label || v.key)));
    ribbon.append(el('span', 'vc-ribbon-end', 'Closest to today'), track, el('span', 'vc-ribbon-end', 'Boldest'));

    // Segmented control: names every variant and follows the carousel on narrow screens.
    const segmented = el('div', 'vc-segmented');
    segmented.setAttribute('role', 'tablist');
    const segs = [];

    const grid = el('div', 'vc-cards');
    const cards = [];
    variants.forEach((variant, index) => {
      const d = variant.decision;
      const name = d.label || variant.key;
      const card = el('article', 'vc-card');
      card.dataset.key = variant.key;

      const cover = el('button', 'vc-row vc-cover');
      cover.type = 'button';
      cover.setAttribute('aria-label', `Look inside ${name}`);
      if (d.previewUrl) {
        const img = el('img', 'vc-cover-img');
        img.src = d.previewUrl;
        img.alt = `${name} cover`;
        img.loading = 'lazy';
        cover.appendChild(img);
      }
      cover.appendChild(el('span', 'vc-cover-cue', 'Look inside'));
      cover.onclick = () => openDialog(item, variant, 'look', controls, pageError);

      const nameRow = el('div', 'vc-row vc-name');
      nameRow.appendChild(el('span', '', name));
      if (variant.chosen) nameRow.appendChild(el('span', 'vc-badge', 'Your direction'));
      const descriptor = el('div', 'vc-row vc-descriptor', d.descriptor || '');

      const taglineRow = el('div', 'vc-row vc-tagline');
      const tag = text(d.tagline);
      if (tag) {
        taglineRow.textContent = `“${tag}”`;
        const font = fontOf(d);
        if (font) taglineRow.style.fontFamily = `"${font}", Georgia, serif`;
      }

      const paletteRow = el('div', 'vc-row vc-palette');
      const colours = (d.palette || []).filter(h => /^#[0-9a-fA-F]{6}$/.test(h || '')).slice(0, 5);
      if (colours.length) {
        paletteRow.setAttribute('role', 'img');
        paletteRow.setAttribute('aria-label', `${name} colour palette`);
        colours.forEach(h => { const s = el('span', 'vc-swatch'); s.style.background = h; paletteRow.appendChild(s); });
      }

      const summaryRow = el('p', 'vc-row vc-summary', d.summary || '');
      const actions = el('div', 'vc-row vc-actions');
      const lookBtn = el('button', 'vc-btn', 'Look inside');
      lookBtn.type = 'button';
      lookBtn.setAttribute('aria-label', `Look inside ${name}`);
      lookBtn.onclick = () => openDialog(item, variant, 'look', controls, pageError);
      const chooseBtn = el('button', 'vc-btn vc-btn-primary', `Choose ${name}`);
      chooseBtn.type = 'button';
      chooseBtn.onclick = () => openDialog(item, variant, 'confirm', controls, pageError);
      controls.push(lookBtn, chooseBtn, cover);
      actions.append(lookBtn, chooseBtn);

      card.append(cover, nameRow, descriptor, taglineRow, paletteRow, summaryRow,
        bullets('Keeps', d.keeps), bullets('Lets go of', d.letsGo), fact('The bet', text(d.bet)), fact('The risk', text(d.risk)), actions);
      grid.appendChild(card);
      cards.push({ card, variant, lookBtn, chooseBtn });

      const seg = el('button', 'vc-seg', name);
      seg.type = 'button';
      seg.setAttribute('role', 'tab');
      seg.onclick = () => card.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });
      segmented.appendChild(seg);
      segs.push(seg);
    });

    // Sticky bar (phones): acts on whichever card is in view.
    const bar = el('div', 'vc-bar');
    const barLook = el('button', 'vc-btn', 'Look inside');
    barLook.type = 'button';
    const barChoose = el('button', 'vc-btn vc-btn-primary', 'Choose');
    barChoose.type = 'button';
    bar.append(barLook, barChoose);
    controls.push(barLook, barChoose);

    let active = Math.max(0, variants.findIndex(v => v.chosen));
    const setActive = (i) => {
      active = i;
      segs.forEach((s, n) => { s.classList.toggle('vc-seg-on', n === i); s.setAttribute('aria-selected', String(n === i)); });
      const v = variants[i];
      const name = v.decision.label || v.key;
      barChoose.textContent = `Choose ${name}`;
      barLook.onclick = () => openDialog(item, v, 'look', controls, pageError);
      barChoose.onclick = () => openDialog(item, v, 'confirm', controls, pageError);
    };
    setActive(active);

    // Track the card nearest the middle of the carousel.
    let frame = 0;
    grid.addEventListener('scroll', () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const mid = grid.scrollLeft + grid.clientWidth / 2;
        let best = 0;
        let bestDist = Infinity;
        cards.forEach(({ card }, n) => {
          const dist = Math.abs(card.offsetLeft + card.offsetWidth / 2 - mid);
          if (dist < bestDist) { best = n; bestDist = dist; }
        });
        if (best !== active) setActive(best);
      });
    }, { passive: true });
    if (active > 0) requestAnimationFrame(() => cards[active].card.scrollIntoView({ inline: 'center', block: 'nearest' }));

    container.append(el('h2', 'vc-heading', heading), el('p', 'vc-intro', intro), ribbon, segmented, pageError, grid, bar,
      el('div', 'vc-note', 'Your progress is saved — you can come back later and pick up where you left off.'));
  };

  const load = async () => {
    status('Loading your directions…');
    let items;
    try {
      const res = await fetch('/api/client/workspace', { headers: HEADERS });
      if (!res.ok) throw new Error('workspace failed');
      items = await res.json();
    } catch (e) {
      status('We could not load your directions. Check your connection and try again.', true);
      return;
    }
    const withChoices = items.filter(i => (i.variants || []).some(v => v.decision));
    const item = config.workItemId ? withChoices.find(i => i.id === config.workItemId) : withChoices[0];
    if (!item) {
      status('There is nothing to choose from yet.', true);
      return;
    }
    render(item);
  };

  if (ctx.simulate) {
    status('Choosing needs a real, signed-in visitor with finished directions — not available in Simulate preview.');
    ctx.complete?.();
    return;
  }
  load();
}
