"""Isolated table only. Compare physical SSTables, not just logical row counts."""
import json, pathlib, re, time, uuid
from scylla_experiments import command, node, wait_state, OUT

def count_tombstones(value):
    total=0
    if isinstance(value,dict):
        for k,v in value.items():
            if k == 'tombstone' and v not in [None,{},[],False]: total+=1
            total+=count_tombstones(v)
    elif isinstance(value,list): total+=sum(count_tombstones(v) for v in value)
    return total

def run():
    OUT.mkdir(parents=True,exist_ok=True)
    nodes=[node('scylla'+str(i)) for i in range(1,4)]; wait_state(nodes[0])
    table='tombstone_'+uuid.uuid4().hex[:12]
    schema=f"CREATE TABLE business_events.{table} (bucket int, id int, value text, PRIMARY KEY (bucket,id)) WITH gc_grace_seconds=600 AND tombstone_gc={{'mode':'timeout'}};"
    result={'table':table,'schema':schema,'snapshots':{},'procedure':[]}
    def cql(sql): return command('exec',nodes[0],'cqlsh','-e',sql)
    def step(text): print(text,flush=True); result['procedure'].append(text)
    def snapshot(name):
        data={}
        for i,n in enumerate(nodes,1):
            command('exec',n,'nodetool','flush','business_events',table)
            # Table name is generated from hex, never user-supplied shell text.
            raw=command('exec',n,'bash','-ec',f'scylla sstable dump-data --keyspace business_events --table {table} --schema-file /tmp/{table}.cql /var/lib/scylla/data/business_events/{table}-*/*-Data.db')
            path=OUT/f'{table}-{name}-node{i}.json'; path.write_text(raw)
            parsed=json.loads(raw)
            data[str(i)]={'dump':path.name,'tombstone_fields':count_tombstones(parsed),'bytes':len(raw)}
        result['snapshots'][name]={'nodes':data,'visible_rows':cql(f'CONSISTENCY ALL; SELECT count(*) FROM business_events.{table} WHERE bucket=1;')}
        (OUT/'tombstones.json').write_text(json.dumps(result,indent=2))
    cql(schema)
    schema_path=OUT/(table+'.cql')
    schema_path.write_text("CREATE KEYSPACE business_events WITH replication = {'class':'NetworkTopologyStrategy','datacenter1':3};\n"+schema.split(' WITH ')[0]+';')
    for n in nodes: command('cp',str(schema_path),n+':/tmp/'+table+'.cql')
    try:
        for n in nodes: command('exec',n,'nodetool','disableautocompaction','business_events',table)
        step('Write 100 rows at ALL and flush all replicas')
        cql('CONSISTENCY ALL; '+''.join(f"INSERT INTO business_events.{table} (bucket,id,value) VALUES (1,{i},'payload-{i}');" for i in range(100)))
        snapshot('before_delete')
        step('Delete 80 rows at ALL, then flush all replicas')
        cql('CONSISTENCY ALL; '+''.join(f'DELETE FROM business_events.{table} WHERE bucket=1 AND id={i};' for i in range(80)))
        snapshot('after_delete')
        step('Major compact before grace expires: tombstones must remain')
        for n in nodes: command('exec',n,'nodetool','compact','business_events',table)
        snapshot('before_grace')
        step('Repair the isolated table with all three replicas online')
        for n in nodes: command('exec',n,'nodetool','repair','business_events',table)
        step('Only after successful repair, reduce isolated table grace to zero and compact all replicas')
        cql(f'ALTER TABLE business_events.{table} WITH gc_grace_seconds=0;')
        for n in nodes: command('exec',n,'nodetool','compact','business_events',table)
        snapshot('after_gc')
        before=result['snapshots']['after_delete']['nodes']; after=result['snapshots']['after_gc']['nodes']
        assert all(before[str(i)]['tombstone_fields']>0 for i in range(1,4)), 'No tombstones found in dumps'
        assert all(after[str(i)]['tombstone_fields']==0 for i in range(1,4)), 'Tombstones still present'
        result['verified']=True
    finally:
        cql(f'ALTER TABLE business_events.{table} WITH gc_grace_seconds=600;')
        for n in nodes: command('exec',n,'nodetool','enableautocompaction','business_events',table)
        (OUT/'tombstones.json').write_text(json.dumps(result,indent=2))
    print('Saved',OUT/'tombstones.json')
if __name__=='__main__': run()
