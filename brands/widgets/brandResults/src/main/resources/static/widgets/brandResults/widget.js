/**
 * Brand Results widget (Genesis Brands) — starts the questionnaire-answers-to-three-directions
 * pipeline for this flow session, polls it, then lets the signed-in visitor choose a direction
 * from watermarked previews. Blocks the page until a direction is chosen, then advances.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };
const POLL_MS = 4000;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function directionLabel(direction) {
  const d = String(direction || '').toLowerCase();
  return d.charAt(0).toUpperCase() + d.slice(1);
}

// What each direction means, in plain words, so a non-designer can compare them.
const DIRECTION_MEANING = {
  ANCHORED: 'Builds on what you already have',
  EVOLVED: 'A confident step forward',
  DISRUPTIVE: 'A bold break from the category'
};

function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function paletteOf(dir) {
  const list = (dir.visualIdentity && dir.visualIdentity.colorPalette) || [];
  return list.map(c => c && c.hex).filter(h => /^#[0-9a-fA-F]{6}$/.test(h || '')).slice(0, 5);
}

function fact(label, value) {
  if (!value) return null;
  const row = el('div', 'br-fact');
  row.append(el('div', 'br-fact-label', label), el('div', 'br-fact-text', value));
  return row;
}

export function mount(container, widget, ctx) {
  const heading = (widget.config || {}).heading || 'Your brand directions are ready';
  ctx.requireCompletion?.();

  const renderStatus = (message) => {
    container.innerHTML = '';
    container.append(el('h2', 'br-heading', heading), el('div', 'br-spinner'), el('div', 'br-status', message));
  };

  const renderError = (message) => {
    container.innerHTML = '';
    const retryBtn = el('button', 'br-retry-btn', 'Try again');
    retryBtn.type = 'button';
    retryBtn.onclick = () => start(true);
    container.append(el('h2', 'br-heading', heading), el('div', 'br-error', message), retryBtn);
  };

  const choose = async (engagementId, direction, controls, errorEl, dialog) => {
    controls.forEach(b => { b.disabled = true; });
    errorEl.textContent = '';
    try {
      const res = await fetch(`/api/engagements/${engagementId}/choose-direction`, {
        method: 'POST', headers: HEADERS, body: JSON.stringify({ direction })
      });
      if (!res.ok) throw new Error('choose failed');
      if (dialog.open) dialog.close();
      ctx.onOutcome?.('next');
    } catch (e) {
      controls.forEach(b => { b.disabled = false; });
      errorEl.textContent = 'We could not save your choice. Check your connection and try again.';
    }
  };

  // One dialog serves both jobs: a closer look at a direction, and the confirmation before
  // choosing it. It is a native <dialog>, so focus is trapped and Escape closes it.
  const openDialog = (engagementId, dir, mode, controls, errorEl) => {
    const name = directionLabel(dir.direction);
    const dialog = el('dialog', 'br-dialog');
    dialog.setAttribute('aria-labelledby', 'br-dialog-title');
    const head = el('div', 'br-dialog-head');
    const title = el('h3', 'br-dialog-title', mode === 'confirm' ? `Choose ${name}?` : `${name} · a closer look`);
    title.id = 'br-dialog-title';
    const closeBtn = el('button', 'br-icon-btn', 'Close');
    closeBtn.type = 'button';
    closeBtn.onclick = () => dialog.close();
    head.append(title, closeBtn);
    dialog.appendChild(head);

    if (mode === 'confirm') {
      dialog.appendChild(el('p', 'br-dialog-text',
        `Everything we build next, from your brand book to your assets, follows the ${name} direction. ` +
        'You can still look at the others first.'));
    } else {
      const img = el('img', 'br-dialog-img');
      img.src = `/api/engagements/${engagementId}/preview/${dir.direction}`;
      img.alt = `${name} preview`;
      dialog.appendChild(img);
      const summary = text(dir.brief && dir.brief.purpose && dir.brief.purpose.summary);
      if (summary) dialog.appendChild(el('p', 'br-dialog-text', summary));
    }

    const dialogError = el('div', 'br-error br-dialog-error');
    dialogError.setAttribute('role', 'alert');
    const actions = el('div', 'br-dialog-actions');
    const back = el('button', 'br-secondary-btn', mode === 'confirm' ? 'Keep looking' : 'Back to directions');
    back.type = 'button';
    back.onclick = () => dialog.close();
    const go = el('button', 'br-choose-btn', mode === 'confirm' ? `Yes, choose ${name}` : `Choose ${name}`);
    go.type = 'button';
    go.onclick = () => {
      if (mode === 'confirm') choose(engagementId, dir.direction, [go, back, ...controls], dialogError, dialog);
      else { dialog.close(); openDialog(engagementId, dir, 'confirm', controls, errorEl); }
    };
    actions.append(back, go);
    dialog.append(dialogError, actions);

    dialog.addEventListener('close', () => dialog.remove());
    container.appendChild(dialog);
    dialog.showModal();
  };

  const renderDirections = (engagementId, results) => {
    container.innerHTML = '';
    container.append(
      el('h2', 'br-heading', heading),
      el('p', 'br-intro', 'Three ways to be you. Compare them, take a closer look, then choose the one that feels right.'));
    const errorEl = el('div', 'br-error br-page-error');
    errorEl.setAttribute('role', 'alert');
    const controls = [];
    const grid = el('div', 'br-cards');
    results.directions.forEach(dir => {
      const name = directionLabel(dir.direction);
      const purpose = (dir.brief && dir.brief.purpose) || {};
      const card = el('article', 'br-card');

      const preview = el('button', 'br-preview');
      preview.type = 'button';
      preview.setAttribute('aria-label', `Take a closer look at ${name}`);
      const img = el('img', 'br-card-img');
      img.src = `/api/engagements/${engagementId}/preview/${dir.direction}`;
      img.alt = `${name} preview`;
      preview.append(img, el('span', 'br-preview-cue', 'Take a closer look'));
      preview.onclick = () => openDialog(engagementId, dir, 'look', controls, errorEl);

      const body = el('div', 'br-card-body');
      body.append(el('div', 'br-card-label', name), el('div', 'br-card-meaning', DIRECTION_MEANING[dir.direction] || ''));
      const tagline = text(dir.copy && dir.copy.tagline);
      if (tagline) body.appendChild(el('div', 'br-card-tagline', `“${tagline}”`));
      const summary = text(purpose.summary);
      if (summary) body.appendChild(el('p', 'br-card-summary', summary));
      [fact('Personality', text(purpose.archetype)), fact('The bet', text(purpose.bet)), fact('The trade-off', text(purpose.risk))]
        .filter(Boolean).forEach(f => body.appendChild(f));
      const colours = paletteOf(dir);
      if (colours.length) {
        const swatches = el('div', 'br-swatches');
        swatches.setAttribute('role', 'img');
        swatches.setAttribute('aria-label', `${name} colour palette`);
        colours.forEach(hex => { const s = el('span', 'br-swatch'); s.style.background = hex; swatches.appendChild(s); });
        body.appendChild(swatches);
      }

      const actions = el('div', 'br-card-actions');
      const lookBtn = el('button', 'br-secondary-btn', 'View');
      lookBtn.type = 'button';
      lookBtn.setAttribute('aria-label', `View ${name}`);
      lookBtn.onclick = () => openDialog(engagementId, dir, 'look', controls, errorEl);
      const chooseBtn = el('button', 'br-choose-btn', 'Select');
      chooseBtn.type = 'button';
      chooseBtn.setAttribute('aria-label', `Select ${name}`);
      chooseBtn.onclick = () => openDialog(engagementId, dir, 'confirm', controls, errorEl);
      controls.push(lookBtn, chooseBtn, preview);
      actions.append(lookBtn, chooseBtn);

      card.append(preview, body, actions);
      grid.appendChild(card);
    });
    container.append(errorEl, grid,
      el('div', 'br-note', 'Your progress is saved — you can come back later and pick up where you left off.'));
  };

  const poll = async (engagementId) => {
    // The visitor has left this page — stop polling for it.
    if (!container.isConnected) return;
    let body;
    try {
      const res = await fetch(`/api/engagements/${engagementId}`, { headers: HEADERS });
      if (!res.ok) throw new Error('poll failed');
      body = await res.json();
    } catch (e) {
      renderError('Lost connection while checking your brand directions.');
      return;
    }
    const status = body.summary.status;
    if (status === 'DONE') {
      if (!body.results || !body.results.directions || body.results.directions.length === 0) {
        renderError('Something went wrong generating your brand directions.');
        return;
      }
      renderDirections(engagementId, body.results);
    } else if (status === 'FAILED') {
      renderError('Something went wrong generating your brand directions.');
    } else {
      renderStatus('Reading your answers and shaping three directions… this can take a few minutes.');
      setTimeout(() => poll(engagementId), POLL_MS);
    }
  };

  const start = async (retry) => {
    renderStatus(retry ? 'Retrying…' : 'Getting started…');
    try {
      const res = await fetch(
        `/api/public/brand-results/flow-sessions/${ctx.session?.token}/widgets/${widget.id}/start${retry ? '?retry=true' : ''}`,
        { method: 'POST', headers: HEADERS });
      if (!res.ok) throw new Error('start failed');
      poll((await res.json()).engagementId);
    } catch (e) {
      renderError('Could not start generating your brand directions.');
    }
  };

  if (ctx.simulate) {
    // The pipeline needs a real, signed-in session (the start endpoint 403s without a client
    // user, which simulated sessions never have), so the admin preview explains instead.
    renderStatus('The brand pipeline only runs for real visitor sessions — not available in Simulate preview.');
    ctx.complete?.();
    return;
  }
  start(false);
}
