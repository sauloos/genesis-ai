/**
 * Payment widget — charges the flow session's whole cart (every Product Options selection
 * made anywhere in the flow) in one checkout. Stripe modes redirect to Stripe Checkout and
 * come back to this page; mock mode succeeds at once. A successful payment advances the flow
 * itself, because nothing else on its page does.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

function formatMoney(cents, currency) {
  const amount = (cents || 0) / 100;
  const symbol = (currency || 'usd').toLowerCase() === 'usd' ? '$' : '';
  return symbol + amount.toLocaleString(undefined, { minimumFractionDigits: amount % 1 === 0 ? 0 : 2, maximumFractionDigits: 2 });
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export async function mount(container, widget, ctx) {
  const config = widget.config || {};
  const base = `/api/public/flow-sessions/${ctx.session?.token}`;
  const buttonLabel = config.buttonLabel || 'Pay now';

  let cart = null, status = null;
  try {
    const res = await fetch(`${base}/cart`, { headers: HEADERS });
    if (res.ok) cart = await res.json();
  } catch (e) { /* no cart yet */ }
  try {
    const res = await fetch(`${base}/widgets/${widget.id}/checkout/status`, { headers: HEADERS });
    if (res.ok) status = await res.json();
  } catch (e) { /* no prior payment attempt */ }

  const renderSuccess = (paid) => {
    container.innerHTML = '';
    container.appendChild(el('h2', 'pay-heading', config.heading || 'Complete your purchase'));
    const box = el('div', 'pay-success');
    box.append(
      el('div', 'pay-success-icon', '✓'),
      el('div', 'pay-success-text', 'Payment complete'),
      el('div', 'pay-success-sub', formatMoney(paid.amountCents, paid.currency) + ' — thank you!'));
    container.appendChild(box);
    // Brief pause so the visitor sees the confirmation before the page navigates away.
    setTimeout(() => ctx.advance?.('next'), 1200);
  };

  const pay = async (btn, errDiv) => {
    btn.disabled = true;
    btn.textContent = 'Processing…';
    try {
      const res = await fetch(`${base}/widgets/${widget.id}/checkout`, {
        method: 'POST', headers: HEADERS, body: JSON.stringify({})
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.message || 'Checkout failed');
      if (body.checkoutUrl) {
        window.location.href = body.checkoutUrl;
        return;
      }
      renderSuccess(body); // mock mode: succeeded at once, no redirect
    } catch (e) {
      btn.disabled = false;
      btn.textContent = buttonLabel;
      errDiv.textContent = e.message || 'Payment could not be started. Please try again.';
      errDiv.style.display = 'block';
    }
  };

  if (status && status.status === 'SUCCEEDED') {
    renderSuccess(status);
    return;
  }

  container.appendChild(el('h2', 'pay-heading', config.heading || 'Complete your purchase'));
  if (!cart || !cart.items || !cart.items.length) {
    container.appendChild(el('div', 'pay-error', 'Select an option above first.'));
    return;
  }

  const summary = el('div', 'pay-summary');
  cart.items.forEach(item => {
    const line = el('div', 'pay-summary-line');
    line.appendChild(el('div', 'pay-summary-line-label', (item.productName || '') + ' — ' + (item.optionName || '')));
    const price = el('div', 'pay-summary-line-price', formatMoney(item.priceCents, item.currency));
    if (item.billingInterval) price.appendChild(el('span', 'pay-interval', ' / ' + item.billingInterval));
    line.appendChild(price);
    summary.appendChild(line);
  });
  summary.appendChild(el('div', 'pay-summary-total', 'Total: ' + formatMoney(cart.totalCents, cart.currency)));
  container.appendChild(summary);

  const btn = el('button', 'pay-btn', buttonLabel);
  btn.type = 'button';
  const errDiv = el('div', 'pay-error');
  errDiv.style.display = 'none';
  btn.onclick = () => pay(btn, errDiv);
  container.append(btn, errDiv);
}
