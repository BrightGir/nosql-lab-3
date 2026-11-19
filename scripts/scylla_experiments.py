"""Host runner: consistency matrix and one-replica outage, restored in finally."""
import argparse, json, os, pathlib, subprocess, time
ROOT=pathlib.Path(__file__).resolve().parents[1]
ENGINE=os.getenv('CONTAINER_ENGINE','podman')
OUT=ROOT/'output/evidence'
def command(*args,check=True):
    p=subprocess.run([ENGINE,*args],cwd=ROOT,text=True,capture_output=True,timeout=180)
    if check and p.returncode: raise RuntimeError(p.stderr or p.stdout)
    return p.stdout

def node(service):
    ids=command('ps','-a','--filter','label=com.docker.compose.project=lab3','--filter','label=com.docker.compose.service='+service,'--format','{{.ID}}').split()
    if len(ids)!=1: raise RuntimeError('Expected one lab3 '+service+' container: '+str(ids))
    return ids[0]

def wait_state(n1, down=False):
    deadline=time.monotonic()+120
    while time.monotonic()<deadline:
        status=command('exec',n1,'nodetool','status')
        lines=[line for line in status.splitlines() if line.startswith(('UN ','DN '))]
        if len(lines)==3 and sum(line.startswith('UN ') for line in lines)==(2 if down else 3): return status
        time.sleep(2)
    raise RuntimeError('Cluster did not reach requested state: '+status)

def run():
    p=argparse.ArgumentParser(); p.add_argument('--count',type=int,default=300); p.add_argument('--workers',type=int,default=8); a=p.parse_args()
    OUT.mkdir(parents=True,exist_ok=True); n1,n3=node('scylla1'),node('scylla3')
    command('cp',str(ROOT/'scripts/scylla_workload.py'),n1+':/tmp/scylla_workload.py')
    result={'environment':command('info','--format','{{json .Host}}'),'states':{},'runs':[]}
    try:
        for phase in ['healthy','one_replica_down','recovered']:
            if phase=='one_replica_down': command('stop','--time','10',n3)
            if phase=='recovered': command('start',n3)
            result['states'][phase]=wait_state(n1,phase=='one_replica_down')
            pairs=[('ONE','ONE'),('ONE','QUORUM'),('QUORUM','QUORUM'),('ALL','ALL')] if phase!='recovered' else [('ONE','QUORUM')]
            for write,read in pairs:
                print(phase,write,read,flush=True)
                raw=command('exec',n1,'python3','/tmp/scylla_workload.py','--write',write,'--read',read,'--count',str(a.count),'--workers',str(a.workers),'--phase',phase)
                result['runs'].append(json.loads(raw))
                (OUT/'consistency.json').write_text(json.dumps(result,indent=2))
    finally:
        command('start',n3,check=False)
    print('Saved',OUT/'consistency.json')
if __name__=='__main__': run()
