export function mount(container) {
  container.className = 'dc-panel';
  container.innerHTML = `
    <div class="dc-panel-label">Catalog</div>
    <div class="dc-coming-soon">Products for your brand — coming soon</div>
  `;
}
