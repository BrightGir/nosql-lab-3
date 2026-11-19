"""Re-check previously captured physical evidence without changing a database."""
import argparse,json
from pathlib import Path
from tombstone_experiment import count_tombstones
from scylla_experiments import OUT

def inspect(path):
    raw=path.read_text();data=json.loads(raw);values={};deletions={}
    for partitions in data['sstables'].values():
        for partition in partitions:
            for row in partition.get('clustering_elements',[]):
                if row['type']!='clustering-row': continue
                key=(partition['key']['raw'],row['key']['raw'])
                for cell in row.get('columns',{}).values():
                    if cell.get('is_live'):values[key]=max(values.get(key,-1),cell['timestamp'])
                if row.get('tombstone'):deletions[key]=max(deletions.get(key,-1),row['tombstone']['timestamp'])
    visible=sum(timestamp>deletions.get(key,-1) for key,timestamp in values.items())
    return {'dump':path.name,'tombstone_fields':count_tombstones(data),'bytes':len(raw.encode()),'visible_rows_from_sstables':visible}

def run():
    p=argparse.ArgumentParser();p.add_argument('table');a=p.parse_args()
    if not a.table.startswith('tombstone_') or not a.table[10:].isalnum():p.error('Expected experimental table name')
    result={'table':a.table,'verification':'Offline re-validation of all 12 previously captured SSTable dumps. No database writes or new measurements.',
            'schema':(OUT/(a.table+'.cql')).read_text(),'snapshots':{}}
    for phase,expected_markers,expected_rows in [('before_delete',0,100),('after_delete',80,20),('before_grace',80,20),('after_gc',0,20)]:
        nodes={str(i):inspect(OUT/f'{a.table}-{phase}-node{i}.json') for i in range(1,4)}
        for data in nodes.values():
            assert data['tombstone_fields']==expected_markers,(phase,data)
            assert data['visible_rows_from_sstables']==expected_rows,(phase,data)
        result['snapshots'][phase]={'nodes':nodes}
    result['verified']=True
    (OUT/'tombstones.json').write_text(json.dumps(result,indent=2));print('Verified 12 saved SSTable dumps: 0 -> 80 -> 80 -> 0 tombstones, 100 -> 20 -> 20 -> 20 live rows on every replica')
if __name__=='__main__':run()
