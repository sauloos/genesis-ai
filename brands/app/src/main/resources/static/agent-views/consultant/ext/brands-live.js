// Genesis Brands' Consultant live view — extends the core chat view with brand copy only.
// Registered by BrandConsultantLiveViewExtension; agent-loader.js passes the core view as `base`.
const BRAND_TEXT = {
  notReady: 'Finish your brand journey to chat with your consultant.',
  placeholder: 'Ask your consultant about your brand…',
  empty: 'Ask anything about your brand — your consultant knows your tagline, tone, palette, and logo.',
};

export function mount(container, agent, ctx, base) {
  return base(container, agent, { ...ctx, text: { ...ctx.text, ...BRAND_TEXT } });
}
