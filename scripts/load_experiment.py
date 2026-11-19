import argparse,json
from scylla_experiments import command,node,wait_state,ROOT,OUT

def run():
    p=argparse.ArgumentParser();p.add_argument('--count',type=int,default=10000);p.add_argument('--workers',type=int,default=16);a=p.parse_args()
    if a.count<1 or a.workers<1: p.error('count and workers must be positive')
    n=node('scylla1');state=wait_state(n)
    command('cp',str(ROOT/'scripts/scylla_workload.py'),n+':/tmp/scylla_workload.py')
    raw=command('exec',n,'python3','/tmp/scylla_workload.py','--count',str(a.count),'--workers',str(a.workers),'--phase','load')
    result=json.loads(raw);result['cluster_state']=state
    OUT.mkdir(parents=True,exist_ok=True);(OUT/'load.json').write_text(json.dumps(result,indent=2));print('Saved',OUT/'load.json')
if __name__=='__main__':run()
