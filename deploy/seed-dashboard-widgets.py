"""Adds the client-facing standing-agent widgets to the existing Client Dashboard flow.

Idempotent: skips any widget type already present on the dashboard page.

Usage:
  ADMIN_USER=... ADMIN_PASS=... GENESIS_API_KEY=... python3 deploy/seed-dashboard-widgets.py https://www.genesisbrands.ai

Run this once after a fresh deployment of genesis-brands, or after adding new
standing-agent widget types that should appear on /live/dashboard.

Widgets added (if not already present):
  sidebar — agentSubscriptions  ("My agents"    — configure standing-agent subscriptions)
  sidebar — channelConnections  ("Social accounts" — connect Meta / LinkedIn accounts)
  main    — agentInbox          ("Inbox"         — approve / reject agent-generated posts)
"""
import base64, json, os, sys, urllib.request

BASE = sys.argv[1].rstrip('/')
AUTH = 'Basic ' + base64.b64encode(f"{os.environ['ADMIN_USER']}:{os.environ['ADMIN_PASS']}".encode()).decode()
KEY  = os.environ['GENESIS_API_KEY']


def call(method, path, body=None):
    req = urllib.request.Request(
        BASE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Authorization': AUTH, 'X-Api-Key': KEY, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


flows = call('GET', '/api/admin/page-flows')
dash  = next((f for f in flows if f['slug'] == 'dashboard' and f.get('rootPrefix') is None), None)
if dash is None:
    print('No "dashboard" flow found — create the Client Dashboard flow first.')
    sys.exit(1)

page_id = dash['startPageId']
existing = {w['widgetType'] for w in call('GET', f'/api/admin/pages/{page_id}/widgets')}

WIDGETS = [
    # (slotKey, orderInSlot, widgetType, label, config)
    ('main',    3, 'agentInbox',         'Inbox',           {}),
    ('sidebar', 1, 'agentSubscriptions', 'My agents',       {}),
    ('sidebar', 2, 'channelConnections', 'Social accounts', {}),
]

added = []
for slot, order, wtype, label, cfg in WIDGETS:
    if wtype in existing:
        print(f'  {wtype} already present — skipped')
        continue
    call('POST', f'/api/admin/pages/{page_id}/widgets',
         {'slotKey': slot, 'orderInSlot': order, 'widgetType': wtype,
          'label': label, 'configJson': json.dumps(cfg)})
    added.append(wtype)
    print(f'  Added {wtype} to {slot} (order {order})')

if not added:
    print('All standing widgets already present — nothing to do.')
else:
    print(f'Done. {len(added)} widget(s) added to /live/dashboard on {BASE}')
