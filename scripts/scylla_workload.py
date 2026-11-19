"""Run inside scylla1 using its bundled driver. Output one JSON document."""
import argparse, concurrent.futures, datetime, json, math, statistics, time, uuid
from cassandra import ConsistencyLevel
from cassandra.cluster import Cluster
from cassandra.policies import DCAwareRoundRobinPolicy, FallthroughRetryPolicy
from cassandra.query import BatchStatement, BatchType

def summary(samples, elapsed):
    good=[x['ms'] for x in samples if x['ok']]
    all_ms=sorted(x['ms'] for x in samples)
    return {'attempts':len(samples),'successes':len(good),'errors':len(samples)-len(good),
            'availability':len(good)/len(samples) if samples else 0,
            'elapsed_seconds':elapsed,'throughput_success_ops_s':len(good)/elapsed,
            'mean_ms':statistics.mean(all_ms) if all_ms else None,
            'p95_ms':all_ms[max(0,math.ceil(.95*len(all_ms))-1)] if all_ms else None,
            'successful_mean_ms':statistics.mean(good) if good else None,'samples':samples}

def main():
    p=argparse.ArgumentParser(); p.add_argument('--write',default='ONE'); p.add_argument('--read',default='QUORUM')
    p.add_argument('--count',type=int,default=300); p.add_argument('--workers',type=int,default=8)
    p.add_argument('--phase',default='healthy'); args=p.parse_args()
    cluster=Cluster(['scylla1','scylla2'],load_balancing_policy=DCAwareRoundRobinPolicy(local_dc='datacenter1'),
                    default_retry_policy=FallthroughRetryPolicy(),connect_timeout=10)
    session=cluster.connect(); session.default_timeout=3
    keys=['result','operation','entity']; tag='bench-'+str(uuid.uuid4()); day=datetime.datetime.now(datetime.timezone.utc).date()
    inserts={k:session.prepare('INSERT INTO business_events.by_'+k+' ('+k+'_key,day,event_id,result,operation,entity,http_status,duration_ms) VALUES (?,?,?,?,?,?,?,?)') for k in keys}
    select=session.prepare('SELECT event_id FROM business_events.by_result WHERE result_key=? AND day=? LIMIT 50')
    write_cl=getattr(ConsistencyLevel,args.write); read_cl=getattr(ConsistencyLevel,args.read)
    def execute(index, mode):
        began=time.perf_counter(); ok=True; error=None; visible=None
        try:
            if mode=='write':
                batch=BatchStatement(batch_type=BatchType.LOGGED,consistency_level=write_cl)
                for key in keys: batch.add(inserts[key],(tag,day,ids[index],tag,tag,tag,200,0))
                session.execute(batch)
            else:
                stmt=select.bind((tag,day)); stmt.consistency_level=read_cl
                rows=list(session.execute(stmt)); visible=len(rows)
                if not rows: raise AssertionError('Read returned no seeded data')
        except Exception as exc: ok=False; error=type(exc).__name__+': '+str(exc)
        return {'ok':ok,'ms':(time.perf_counter()-began)*1000,'error':error,'rows':visible}
    ids=[uuid.uuid1() for _ in range(args.count)]
    # Seed all replicas so read availability is measured independently of write success.
    for key in keys:
        stmt=inserts[key].bind((tag,day,uuid.uuid1(),tag,tag,tag,200,0)); stmt.consistency_level=ConsistencyLevel.QUORUM
        session.execute(stmt)
    result={'phase':args.phase,'write_cl':args.write,'read_cl':args.read,'workers':args.workers,
            'count':args.count,'seed':tag,'time_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'server':session.execute('SELECT release_version FROM system.local').one().release_version,
            'retry_policy':'FallthroughRetryPolicy','measurement':'client CQL; one write = logged batch into 3 tables; read = up to 50 rows'}
    for mode in ['write','read']:
        # Warm connections/prepared statements without including warm-up in observations.
        for i in range(min(10,args.count)): execute(i,mode)
        start=time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            samples=list(pool.map(lambda i:execute(i,mode),range(args.count)))
        result[mode]=summary(samples,time.perf_counter()-start)
    cluster.shutdown(); print(json.dumps(result,default=str))
if __name__=='__main__': main()
