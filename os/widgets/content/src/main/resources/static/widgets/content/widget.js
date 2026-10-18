/**
 * Content widget — heading + body text (default), a raw HTML fragment, an image, a full
 * HTML file in a sandboxed iframe, or a header hero (theme logo + eyebrow + title), with
 * optional width/height boundaries.
 */
function applyBoundaries(el, config) {
  if (config.widthMode === 'percent' && config.width) el.style.width = config.width + '%';
  else if (config.widthMode === 'fixed' && config.width) el.style.width = config.width + 'px';
  if (config.heightMode === 'percent' && config.height) el.style.height = config.height + '%';
  else if (config.heightMode === 'fixed' && config.height) el.style.height = config.height + 'px';
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

export function mount(container, widget) {
  const config = widget.config || {};
  const mode = config.contentMode || 'text';
  let target = container;

  if (mode === 'html') {
    container.innerHTML = config.html || '';
  } else if (mode === 'image') {
    target = el('img', 'wc-image');
    target.src = config.imageUrl || '';
    target.alt = config.imageAlt || '';
    container.appendChild(target);
  } else if (mode === 'htmlFile') {
    target = el('iframe', 'wc-iframe');
    target.src = config.htmlFileUrl || '';
    // allow-top-navigation lets an embedded full page's own links (e.g. "Sign in")
    // navigate the real tab instead of loading inside the iframe.
    target.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms allow-popups allow-top-navigation');
    target.setAttribute('frameborder', '0');
    container.appendChild(target);
  } else if (mode === 'header') {
    const hero = el('div', 'wc-hero');
    const logo = el('div', 'wc-hero-logo');
    logo.setAttribute('role', 'img');
    logo.setAttribute('aria-label', config.heading || 'Logo');
    hero.appendChild(logo);
    if (config.label) hero.appendChild(el('div', 'wc-hero-label', config.label));
    hero.appendChild(el('h1', null, config.heading || ''));
    container.appendChild(hero);
  } else {
    container.appendChild(el('h2', null, config.heading || ''));
    container.appendChild(el('p', null, config.body || ''));
  }

  // Image and iframe size themselves; a percent height resolves against the slot, so the
  // wrapper passes the slot's height through.
  if (target !== container && config.heightMode === 'percent') container.classList.add('wc-fill');
  applyBoundaries(target, config);
}
