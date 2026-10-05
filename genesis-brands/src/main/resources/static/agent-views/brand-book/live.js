import { fetchEngagementDetail, findDirectionOutput, submitRegeneration, pollRegeneration, renderApprovalGate }
  from '/agent-views/_shared/regenerate.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export async function mount(container, agent, ctx) {
  container.className = 'av-view';
  container.innerHTML = '<div class="av-value">Loading current brand book…</div>';

  let detail, dir;
  try {
    detail = await fetchEngagementDetail(agent.engagementId);
    dir = findDirectionOutput(detail, agent.direction);
  } catch (err) {
    container.innerHTML = `<div class="av-error">Could not load current output: ${esc(err.message)}</div>`;
    return;
  }
  if (!dir || !dir.brandBook) {
    container.innerHTML = '<div class="av-value">No brand book generated yet for this direction.</div>';
    return;
  }

  render(container, agent, ctx, dir.brandBook);
}

function renderFields(el, brandBook) {
  el.innerHTML = `
    <div class="av-section"><div class="av-label">Welcome Note</div><div class="av-value">${esc(brandBook.welcomeNote)}</div></div>
    <div class="av-section"><div class="av-label">Logo Usage Guidelines</div><div class="av-value">${esc(brandBook.logoUsageGuidelines)}</div></div>
    <div class="av-section"><div class="av-label">Color Usage Guidelines</div><div class="av-value">${esc(brandBook.colorUsageGuidelines)}</div></div>
    <div class="av-section"><div class="av-label">Typography Usage Guidelines</div><div class="av-value">${esc(brandBook.typographyUsageGuidelines)}</div></div>
  `;
}

function render(container, agent, ctx, brandBook) {
  container.innerHTML = `
    <div class="av-fields"></div>
    <div class="av-section">
      <div class="av-label">Feedback for this regeneration (optional)</div>
      <textarea class="av-feedback" placeholder="e.g. Expand the logo don'ts section…"></textarea>
    </div>
    <div class="av-footer">
      <button class="av-submit" type="button">Regenerate</button>
      <span class="av-status"></span>
    </div>
  `;
  renderFields(container.querySelector('.av-fields'), brandBook);
  wireSubmit(container, agent, ctx);
}

function wireSubmit(container, agent, ctx) {
  const button = container.querySelector('.av-submit');
  const status = container.querySelector('.av-status');
  const feedback = container.querySelector('.av-feedback');

  button.onclick = async () => {
    button.disabled = true;
    status.textContent = 'Queuing…';
    container.querySelector('.av-error')?.remove();
    try {
      const queued = await submitRegeneration(agent.engagementId, agent.agentId, agent.direction, feedback.value.trim());
      const job = await pollRegeneration(agent.engagementId, queued.id, (j) => {
        status.textContent = j.status === 'RUNNING' ? 'Regenerating…' : 'Queued…';
      });
      renderApprovalGate(container, {
        engagementId: agent.engagementId,
        job,
        renderDraft: (el, draft) => renderFields(el, draft.brandBook),
        onApprove: async () => {
          ctx.onAssetGenerated?.();
          const detail = await fetchEngagementDetail(agent.engagementId);
          const dir = findDirectionOutput(detail, agent.direction);
          render(container, agent, ctx, dir.brandBook);
        },
        onDiscard: async () => {
          const detail = await fetchEngagementDetail(agent.engagementId);
          const dir = findDirectionOutput(detail, agent.direction);
          render(container, agent, ctx, dir.brandBook);
        },
      });
    } catch (err) {
      button.disabled = false;
      status.textContent = '';
      const errEl = document.createElement('div');
      errEl.className = 'av-error';
      errEl.textContent = err.message || 'Regeneration failed.';
      container.querySelector('.av-footer').after(errEl);
    }
  };
}
