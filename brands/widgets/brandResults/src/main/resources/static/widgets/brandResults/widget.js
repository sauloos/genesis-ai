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

  const choose = async (engagementId, direction, grid) => {
    grid.querySelectorAll('.br-choose-btn').forEach(b => b.disabled = true);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/choose-direction`, {
        method: 'POST', headers: HEADERS, body: JSON.stringify({ direction })
      });
      if (!res.ok) throw new Error('choose failed');
      ctx.onOutcome?.('next');
    } catch (e) {
      grid.querySelectorAll('.br-choose-btn').forEach(b => b.disabled = false);
      alert('Could not save your choice. Please try again.');
    }
  };

  const renderDirections = (engagementId, results) => {
    container.innerHTML = '';
    container.appendChild(el('h2', 'br-heading', heading));
    const grid = el('div', 'br-cards');
    results.directions.forEach(dir => {
      const card = el('div', 'br-card');
      const img = el('img', 'br-card-img');
      img.src = `/api/engagements/${engagementId}/preview/${dir.direction}`;
      img.alt = directionLabel(dir.direction) + ' preview';
      card.append(img, el('div', 'br-card-label', directionLabel(dir.direction)));
      if (dir.copy && dir.copy.tagline) card.appendChild(el('div', 'br-card-tagline', dir.copy.tagline));
      const chooseBtn = el('button', 'br-choose-btn', 'Choose this direction');
      chooseBtn.type = 'button';
      chooseBtn.onclick = () => choose(engagementId, dir.direction, grid);
      card.appendChild(chooseBtn);
      grid.appendChild(card);
    });
    container.append(grid,
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
