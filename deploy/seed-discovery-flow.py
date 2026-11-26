"""Creates the client-facing brand discovery PageFlow: one public page carrying the
Purpose Playbook discovery questionnaire, ending in CREATE_ENGAGEMENT.

Usage:
  ADMIN_USER=... ADMIN_PASS=... GENESIS_API_KEY=... \
    python3 deploy/seed-discovery-flow.py http://localhost:8099 [--live] [--publish-questionnaire]

Idempotent: does nothing if a flow with the slug "discovery" already exists. The
questionnaire itself is seeded separately by deploy/seed-purpose-playbook-questionnaire.py
and is looked up here by title.

Left NOT live unless --live is passed, deliberately. The flow ends in CREATE_ENGAGEMENT
and carries no product or payment page, so going live hands every visitor a full
engagement (3 directions x 5 specialist agents) for free. Enable it only once it sits
behind the product/payment pages of a real journey, or on a local/staging box.
"""
import base64
import json
import os
import sys
import urllib.request

ARGS = [a for a in sys.argv[1:] if not a.startswith('--')]
FLAGS = {a for a in sys.argv[1:] if a.startswith('--')}
if not ARGS:
    print(__doc__)
    sys.exit(2)

BASE = ARGS[0].rstrip('/')
AUTH = 'Basic ' + base64.b64encode(f"{os.environ['ADMIN_USER']}:{os.environ['ADMIN_PASS']}".encode()).decode()
KEY = os.environ['GENESIS_API_KEY']

QUESTIONNAIRE_TITLE = 'Purpose Playbook discovery'
SLUG = 'discovery'


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={'Authorization': AUTH, 'X-Api-Key': KEY,
                                          'Content-Type': 'application/json'})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


existing = next((f for f in call('GET', '/api/admin/page-flows') if f['slug'] == SLUG), None)
if existing:
    print(f'A "{SLUG}" flow already exists ({existing["id"]}, live={existing.get("live")}) — nothing to do.')
    sys.exit(0)

matches = [q for q in call('GET', '/api/questionnaires') if q['title'] == QUESTIONNAIRE_TITLE]
if not matches:
    sys.exit(f'No questionnaire titled "{QUESTIONNAIRE_TITLE}" — run '
             f'deploy/seed-purpose-playbook-questionnaire.py first.')
if len(matches) > 1:
    sys.exit(f'{len(matches)} questionnaires titled "{QUESTIONNAIRE_TITLE}" — delete the '
             f'duplicates, or the flow would point at an arbitrary one: '
             f'{", ".join(q["id"] for q in matches)}')
questionnaire = matches[0]

if '--publish-questionnaire' in FLAGS and not questionnaire.get('publishedAt'):
    call('POST', f'/api/questionnaires/{questionnaire["id"]}/publish', {'publishAt': None})
    print(f'Published questionnaire {questionnaire["id"]}')

flow = call('POST', '/api/admin/page-flows', {'name': 'Brand discovery', 'slug': SLUG})
page = call('POST', f'/api/admin/page-flows/{flow["id"]}/pages', {'name': 'Discovery'})
# Public on purpose: discovery is the top of the funnel. The visitor registers afterwards,
# at the point FlowEngagementTrigger.nextUrl sends them to claim the engagement.
call('PUT', f'/api/admin/pages/{page["id"]}',
     {'name': 'Discovery', 'requiresAuth': False, 'errorPage': False, 'endPage': False,
      'bordered': True})
call('POST', f'/api/admin/pages/{page["id"]}/widgets',
     {'slotKey': 'main', 'widgetType': 'questionnaire', 'label': 'Purpose Playbook discovery',
      'configJson': json.dumps({'questionnaireId': questionnaire['id']})})
call('PUT', f'/api/admin/page-flows/{flow["id"]}/start', {'startPageId': page['id']})
# The questionnaire widget calls ctx.advance('next') itself on submit, so there is no separate
# outcome button. Every edge has to exist: FlowSessionService.advance refuses an outcome with no
# transition row, so a missing one returns 400 and no engagement is ever created.
#
# "Your logo": the logoAssessment widget scores the logo the client uploaded and records
# keep / evolve / start fresh. A client with no logo passes straight through
# (the widget calls onOutcome itself). Its answers reach the engagement through
# LogoAssessmentAnswerSource, so it only has to sit before the flow end.
logo_page = call('POST', f'/api/admin/page-flows/{flow["id"]}/pages', {'name': 'Your logo'})
call('PUT', f'/api/admin/pages/{logo_page["id"]}',
     {'name': 'Your logo', 'requiresAuth': False, 'errorPage': False, 'endPage': False,
      'bordered': True})
call('POST', f'/api/admin/pages/{logo_page["id"]}/widgets',
     {'slotKey': 'main', 'widgetType': 'logoAssessment', 'label': 'Logo assessment',
      'configJson': '{}'})
call('PUT', f'/api/admin/pages/{page["id"]}/transitions',
     {'outcomeKey': 'next', 'targetKind': 'PAGE', 'targetPageId': logo_page['id']})
call('PUT', f'/api/admin/pages/{logo_page["id"]}/transitions',
     {'outcomeKey': 'next', 'targetKind': 'FLOW_END', 'targetPageId': None})
call('PUT', f'/api/admin/page-flows/{flow["id"]}/end', {'endAction': 'CREATE_ENGAGEMENT'})

if '--live' in FLAGS:
    call('PUT', f'/api/admin/page-flows/{flow["id"]}/live')

print(f'Created the "{SLUG}" flow ({flow["id"]}) on {BASE}, questionnaire {questionnaire["id"]}')
print(f'  URL once live: {BASE}/live/{SLUG}')
if '--live' in FLAGS:
    print('  live: yes')
else:
    print(f'  live: NO. To go live:  curl -u $ADMIN_USER:$ADMIN_PASS -H "X-Api-Key: $GENESIS_API_KEY" '
          f'-X PUT {BASE}/api/admin/page-flows/{flow["id"]}/live')
if not questionnaire.get('publishedAt') and '--publish-questionnaire' not in FLAGS:
    print(f'  NOTE: questionnaire {questionnaire["id"]} is not published. The widget still runs it, '
          f'but publish it before sending real clients:  curl ... -X POST '
          f"{BASE}/api/questionnaires/{questionnaire['id']}/publish -d '{{\"publishAt\":null}}'")
