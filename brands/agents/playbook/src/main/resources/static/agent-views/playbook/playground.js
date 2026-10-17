import { mountSlotPlayground } from '/agent-views/_shared/slotPlayground.js';

/** Playbook agent's Playground view — assembles from accepted Copy / Visual Identity / Logo runs. */
export function mount(container, agent, ctx) {
  return mountSlotPlayground(container, {
    agentId: 'playbook',
    resultField: 'playbook',
    sourceRuns: [
      { field: 'copy', agentId: 'copy', label: 'Copy' },
      { field: 'visualIdentity', agentId: 'visual-identity', label: 'Visual Identity' },
      { field: 'logo', agentId: 'logo', label: 'Logo' },
    ],
    renderOutput: (o, h) => `
      ${h.block('Strategic summary', o.strategicSummary)}
      ${h.block('Brand foundations', o.brandFoundations)}
      ${h.block('Verbal identity', o.verbalIdentity)}
      ${h.block('Visual identity', o.visualIdentitySummary)}
      ${h.block('Logo rationale', o.logoRationale)}
      ${h.bulletBlock('Recommended applications', o.recommendedApplications)}`,
    renderAudit: (o, h) => `
      ${h.block('Strategic summary', o.strategicSummary)}
      ${h.bulletBlock('Recommended applications', o.recommendedApplications)}`,
  }, ctx);
}
