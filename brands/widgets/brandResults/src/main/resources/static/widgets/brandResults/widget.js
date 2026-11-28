/**
 * Brand Results widget (Genesis Brands) — starts the questionnaire-answers-to-three-directions
 * pipeline for this flow session and polls it. Once the directions exist it hands over to the
 * core variantChooser widget (same module, mounted through the loader), which owns comparing,
 * looking inside and choosing. Blocks the page until a direction is chosen, then advances.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };
const POLL_MS = 4000;

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
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

  // The chooser is its own widget, never a copy of it: same module the loader gives any host.
  const renderDirections = async (engagementId) => {
    container.innerHTML = '';
    const slot = el('div', 'widget-variant-chooser');
    container.appendChild(slot);
    try {
      const { mountWidget } = await import('/widgets/loader.js');
      await mountWidget('variantChooser', slot,
        { id: widget.id, slotKey: widget.slotKey, orderInSlot: widget.orderInSlot, widgetType: 'variantChooser',
          config: { heading, workItemId: engagementId } },
        ctx);
    } catch (e) {
      renderError('We could not show your brand directions. Please try again.');
    }
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
      renderDirections(engagementId);
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
