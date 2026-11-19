import json
from urllib.parse import urlencode
from scylla_experiments import command,node,ROOT,OUT
from scylla_integration import call

def run():
    n=node('scylla1');command('cp',str(ROOT/'scripts/scylla_fixtures.py'),n+':/tmp/scylla_fixtures.py')
    fixture=json.loads(command('exec',n,'python3','/tmp/scylla_fixtures.py'))
    results=[]
    for dimension in ['result','operation','entity']:
        for sort in ['asc','desc']:
            p={dimension:fixture['tag'],'from':fixture['from'],'to':fixture['to'],'limit':1,'sort':sort}; ids=[]
            while True:
                page=call('/api/business-events?'+urlencode(p));ids += [row['id'] for row in page['events']]
                if not page['nextCursor']:break
                assert len(ids)<=4
                p['cursor']=page['nextCursor']
            assert ids==(fixture['ids'] if sort=='asc' else fixture['ids'][::-1]), (dimension,sort,ids,fixture)
            results.append({'dimension':dimension,'sort':sort,'ids':ids})
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'boundaries.json').write_text(json.dumps({'fixture':fixture,'results':results},indent=2));print('Daily boundaries, same-timestamp ties and cursor pagination passed')
if __name__=='__main__':run()
