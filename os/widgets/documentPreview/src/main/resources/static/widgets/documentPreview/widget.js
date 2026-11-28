/**
 * Document preview (core). Lists the documents the signed-in visitor may preview and opens each in
 * the shared viewer (./viewer.js). In Simulate mode it shows sample documents and a sample page
 * sequence, so the admin preview never needs a real client or any real document.
 */
import { openDocumentPreview } from '/widgets/documentPreview/viewer.js';

const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

const SAMPLE_PAGES = [
  { index: 0, chapter: 'Cover', access: 'SHOW', label: 'Cover', description: '' },
  { index: 1, chapter: 'Brand Statement', access: 'SHOW', label: 'Brand Statement', description: '' },
  { index: 2, chapter: 'Mission', access: 'WITHHOLD', label: 'Mission', description: 'Why the brand exists and who it serves. Not shown in the preview.' },
  { index: 3, chapter: 'Values', access: 'WITHHOLD', label: 'Values', description: 'The four values, each with a worked example. Not shown in the preview.' },
  { index: 4, chapter: 'Logo System', access: 'SHOW', label: 'Logo System', description: '' },
  { index: 5, chapter: 'Closing', access: 'SHOW', label: 'Closing', description: '' },
];

export function mount(container, widget, ctx = {}) {
  const config = widget?.config || {};
  container.appendChild(el('h2', 'dp-heading', config.heading || 'Look inside your documents'));
  container.appendChild(el('p', 'dp-intro', config.intro
    || 'A guided look at each document. Pages that are not part of the preview are described, not shown.'));
  const list = el('div', 'dp-list');
  const status = el('p', 'dp-status');
  status.setAttribute('role', 'status');
  container.append(status, list);

  const card = (doc, open) => {
    const b = el('button', 'dp-doc');
    b.type = 'button';
    b.append(el('span', 'dp-doc-title', doc.title || 'Document'), el('span', 'dp-doc-sub', doc.variantLabel || ''));
    b.onclick = async () => {
      b.disabled = true;
      try { await open(); }
      catch (e) { status.textContent = 'This preview is not available right now. Please try again later.'; status.className = 'dp-status dp-status-error'; }
      finally { b.disabled = false; }
    };
    list.appendChild(b);
  };

  if (ctx.simulate) {
    status.textContent = 'Simulate preview: sample documents, no real pages are served.';
    ['Anchored', 'Evolved', 'Disruptive'].forEach(label => card(
      { title: 'Sample brand book', variantLabel: label },
      () => openDocumentPreview({ manifest: { title: 'Sample brand book', variantLabel: label, pages: SAMPLE_PAGES } })));
    return;
  }

  status.textContent = 'Loading your documents…';
  (async () => {
    try {
      const res = await fetch('/api/client/previews', { headers: HEADERS, cache: 'no-store' });
      if (!res.ok) throw new Error('list failed');
      const docs = await res.json();
      status.textContent = docs.length ? '' : 'Nothing to preview yet.';
      docs.forEach(d => card(d, () => openDocumentPreview({ workItemId: d.workItemId, variantKey: d.variantKey })));
    } catch (e) {
      status.textContent = 'Previews are not available right now.';
      status.className = 'dp-status dp-status-error';
    }
  })();
}
