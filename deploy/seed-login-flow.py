"""Creates the root /login and /register PageFlows (one page each, the login widget) on an
app that has none.

Usage:
  ADMIN_USER=... ADMIN_PASS=... GENESIS_API_KEY=... python3 deploy/seed-login-flow.py https://platform.genesisbrands.ai

Idempotent per slug: skips whichever of the two already exists. Both are day-one flows —
the login widget itself redirects to ?next, and a CREATE_ENGAGEMENT flow sends an anonymous
visitor to /register?next=... to claim what they just generated, so an app without the
register flow hands that visitor a 404.
"""
import base64
import json
import os
import sys
import urllib.request

BASE = sys.argv[1].rstrip('/')
AUTH = 'Basic ' + base64.b64encode(f"{os.environ['ADMIN_USER']}:{os.environ['ADMIN_PASS']}".encode()).decode()
KEY = os.environ['GENESIS_API_KEY']


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={'Authorization': AUTH, 'X-Api-Key': KEY,
                                          'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


existing = {f['slug'] for f in call('GET', '/api/admin/page-flows')}


def seed(slug, name, initial_tab):
    if slug in existing:
        print(f'A "{slug}" flow already exists — nothing to do.')
        return
    flow = call('POST', '/api/admin/page-flows', {'name': name, 'slug': slug})
    call('PUT', f'/api/admin/page-flows/{flow["id"]}/root-prefix', {'rootPrefix': ''})
    page = call('POST', f'/api/admin/page-flows/{flow["id"]}/pages', {'name': name})
    call('PUT', f'/api/admin/pages/{page["id"]}',
         {'name': name, 'requiresAuth': False, 'errorPage': False, 'endPage': False, 'bordered': True})
    call('POST', f'/api/admin/pages/{page["id"]}/widgets',
         {'slotKey': 'main', 'widgetType': 'login', 'label': 'login',
          'configJson': json.dumps({'redirectToNextParam': True, 'initialTab': initial_tab})})
    call('PUT', f'/api/admin/page-flows/{flow["id"]}/start', {'startPageId': page['id']})
    call('PUT', f'/api/admin/page-flows/{flow["id"]}/live')
    print(f'Created /{slug} ({flow["id"]}) on {BASE}')


seed('login', 'Sign in', 'signin')
seed('register', 'Register', 'signup')
