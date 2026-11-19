"""Live acceptance test; requires the full stack. Saves only observed results."""
import json, os, uuid, time
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from urllib.error import HTTPError
from pathlib import Path
BASE = os.getenv('APP_URL', 'http://localhost:18080')
def call(path, data=None, method=None):
    req = Request(BASE+path, data=None if data is None else json.dumps(data).encode(),
                  headers={'Content-Type':'application/json'}, method=method)
    with urlopen(req, timeout=20) as r: return json.load(r)
def run():
    tag = 'audit-'+str(uuid.uuid4())
    created = call('/api/events', {'title':tag,'type':'MEETING','startsAt':'2026-12-01T12:00:00Z','capacity':30})
    draft = call('/api/drafts', {'eventId':created['id'],'managerId':tag,'quantity':2,'comment':'lab3','ttlSeconds':600})
    order = call('/api/drafts/'+draft['id']+'/submit', {}, 'POST')
    assert call('/api/orders/'+order['id'])['id'] == order['id']
    missing=str(uuid.uuid4())
    try:
        call('/api/drafts/'+missing+'/submit', {}, 'POST')
        raise AssertionError('Missing draft was accepted')
    except HTTPError as e: assert e.code == 409
    params = {'from':'2026-11-01T00:00:00Z','to':'2026-12-01T00:00:00Z','limit':1,'sort':'asc'}
    # Bound relative to now so the acceptance test remains reusable.
    from datetime import datetime, timezone, timedelta
    now=datetime.now(timezone.utc)
    params.update({'from':(now-timedelta(days=1)).isoformat(timespec='milliseconds'),'to':(now+timedelta(days=1)).isoformat(timespec='milliseconds')})
    # Wait for these exact requests, not historical events from an earlier run.
    expected={draft['id']:{('CREATE_DRAFT','SUCCESS'),('SUBMIT_DRAFT','SUCCESS')},
              missing:{('SUBMIT_DRAFT','FAILURE')},created['id']:{('CREATE_EVENT','SUCCESS')}}
    deadline=time.monotonic()+15
    for entity,wanted in expected.items():
        while True:
            page=call('/api/business-events?'+urlencode(dict(params,entity=entity,limit=200)))
            actual={(e['operation'],e['result']) for e in page['events']}
            if wanted<=actual: break
            if time.monotonic()>=deadline: raise AssertionError((entity,wanted,actual))
            time.sleep(.1)
    evidence={'exact_operations':{entity:sorted(wanted) for entity,wanted in expected.items()}}
    for kind,value in [('result','SUCCESS'),('operation','SUBMIT_DRAFT'),('entity',draft['id'])]:
        p=dict(params, **{kind:value}); rows=[]; cursors=set()
        while True:
            page=call('/api/business-events?'+urlencode(p)); rows+=page['events']
            cursor=page['nextCursor']
            if not cursor: break
            assert cursor not in cursors
            cursors.add(cursor); p['cursor']=cursor
        assert rows, kind
        assert len({x['id'] for x in rows}) == len(rows)
        assert [x['occurredAt'] for x in rows] == sorted(x['occurredAt'] for x in rows)
        desc=call('/api/business-events?'+urlencode(dict(params, **{kind:value}, sort='desc',limit=200)))
        assert desc['events'][0]['id']==rows[-1]['id']
        evidence[kind]={'count':len(rows),'pages':len(cursors)+1,'sample':rows[-1]}
    failed=call('/api/business-events?'+urlencode(dict(params,result='FAILURE',limit=200)))
    assert any(x['operation']=='SUBMIT_DRAFT' for x in failed['events'])
    for bad in [dict(params,result='SUCCESS',operation='SUBMIT_DRAFT'),dict(params,result='SUCCESS',cursor='garbage'),dict(params,result='SUCCESS',sort='wrong')]:
        try: call('/api/business-events?'+urlencode(bad)); raise AssertionError('accepted invalid query')
        except HTTPError as e: assert e.code==400
    Path('output/evidence').mkdir(parents=True,exist_ok=True)
    Path('output/evidence/scylla-integration.json').write_text(json.dumps(evidence,indent=2))
    print(json.dumps(evidence,indent=2))
if __name__=='__main__': run()
