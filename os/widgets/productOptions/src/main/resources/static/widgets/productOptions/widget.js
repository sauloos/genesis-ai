/**
 * Product Options widget — renders one configured Product as selectable option cards (tiers).
 * Selection only, no payment: the choice lands in the flow session's shared cart, which a
 * later payment widget charges. A required instance blocks the page until a tier is picked.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

function formatMoney(cents, currency) {
  const amount = (cents || 0) / 100;
  const symbol = (currency || 'usd').toLowerCase() === 'usd' ? '$' : '';
  return symbol + amount.toLocaleString(undefined, { minimumFractionDigits: amount % 1 === 0 ? 0 : 2, maximumFractionDigits: 2 });
}

function parseFeatures(json) {
  if (!json) return [];
  try { return JSON.parse(json); } catch (e) { return []; }
}

function showError(container, message) {
  container.innerHTML = '';
  const err = document.createElement('div');
  err.className = 'po-error';
  err.textContent = message;
  container.appendChild(err);
}

export async function mount(container, widget, ctx) {
  const config = widget.config || {};
  const required = config.required === true || config.required === 'true';
  if (required) ctx.requireCompletion?.();

  if (!config.productId) {
    showError(container, 'This widget has no product configured.');
    return;
  }

  let product, options;
  try {
    const res = await fetch(`/api/products/${config.productId}`, { headers: HEADERS });
    if (!res.ok) throw new Error('product fetch failed');
    const detail = await res.json();
    product = detail.product;
    options = (detail.options || []).filter(o => o.active);
  } catch (e) {
    showError(container, 'Could not load this product.');
    return;
  }

  const selectionUrl = `/api/public/flow-sessions/${ctx.session?.token}/widgets/${widget.id}/selection`;
  let selectedOptionId = null;
  try {
    const res = await fetch(selectionUrl, { headers: HEADERS });
    if (res.ok) selectedOptionId = (await res.json()).optionId || null;
  } catch (e) { /* no prior selection — proceed unselected */ }

  const select = async (optionId) => {
    try {
      const res = await fetch(selectionUrl, {
        method: 'POST', headers: HEADERS,
        body: JSON.stringify({ productId: product.id, optionId })
      });
      if (!res.ok) throw new Error('selection failed');
      selectedOptionId = optionId;
      render();
    } catch (e) {
      alert('Could not save your selection. Please try again.');
    }
  };

  const render = () => {
    container.innerHTML = '';
    if (required && selectedOptionId) ctx.complete?.();

    const heading = document.createElement('h2');
    heading.className = 'po-heading';
    heading.textContent = config.heading || product.name;
    container.appendChild(heading);

    const grid = document.createElement('div');
    grid.className = 'po-cards';
    options.forEach(o => {
      const selected = o.id === selectedOptionId;
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'po-card' + (selected ? ' selected' : '');

      const name = document.createElement('div');
      name.className = 'po-name';
      name.textContent = o.name;
      card.appendChild(name);

      const price = document.createElement('div');
      price.className = 'po-price';
      price.textContent = formatMoney(o.priceCents, o.currency);
      if (o.billingInterval) {
        const interval = document.createElement('span');
        interval.className = 'po-interval';
        interval.textContent = ' / ' + o.billingInterval;
        price.appendChild(interval);
      }
      card.appendChild(price);

      if (o.description) {
        const desc = document.createElement('div');
        desc.className = 'po-desc';
        desc.textContent = o.description;
        card.appendChild(desc);
      }

      const features = parseFeatures(o.featuresJson);
      if (features.length) {
        const ul = document.createElement('ul');
        ul.className = 'po-features';
        features.forEach(f => {
          const li = document.createElement('li');
          li.textContent = f;
          ul.appendChild(li);
        });
        card.appendChild(ul);
      }

      const selectBtn = document.createElement('div');
      selectBtn.className = 'po-select-btn';
      selectBtn.textContent = selected ? 'Selected' : 'Select';
      card.appendChild(selectBtn);

      card.onclick = () => select(o.id);
      grid.appendChild(card);
    });
    container.appendChild(grid);
  };

  render();
}
