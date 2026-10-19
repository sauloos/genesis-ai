"""Creates the root /login PageFlow (one page, the login widget) on an app that has none.

Usage:
  ADMIN_USER=... ADMIN_PASS=... GENESIS_API_KEY=... python3 deploy/seed-login-flow.py https://platform.genesisbrands.ai

Does nothing if a flow with the slug "login" already exists.
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


if any(f['slug'] == 'login' for f in call('GET', '/api/admin/page-flows')):
    print('A "login" flow already exists — nothing to do.')
    sys.exit(0)

flow = call('POST', '/api/admin/page-flows', {'name': 'Sign in', 'slug': 'login'})
call('PUT', f'/api/admin/page-flows/{flow["id"]}/root-prefix', {'rootPrefix': ''})
page = call('POST', f'/api/admin/page-flows/{flow["id"]}/pages', {'name': 'Sign in'})
call('PUT', f'/api/admin/pages/{page["id"]}',
     {'name': 'Sign in', 'requiresAuth': False, 'errorPage': False, 'endPage': False, 'bordered': True})
call('POST', f'/api/admin/pages/{page["id"]}/widgets',
     {'slotKey': 'main', 'widgetType': 'login', 'label': 'login',
      'configJson': json.dumps({'redirectToNextParam': True, 'initialTab': 'signin'})})
call('PUT', f'/api/admin/page-flows/{flow["id"]}/start', {'startPageId': page['id']})
call('PUT', f'/api/admin/page-flows/{flow["id"]}/live')
print(f'Created /login ({flow["id"]}) on {BASE}')
