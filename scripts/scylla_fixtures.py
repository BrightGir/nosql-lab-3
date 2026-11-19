"""Inside scylla1: deterministic boundary fixtures, unique partition keys per run."""
import datetime,json,uuid
from cassandra import ConsistencyLevel
from cassandra.cluster import Cluster
from cassandra.util import uuid_from_time
from cassandra.query import BatchStatement
c=Cluster(['scylla1']);s=c.connect();tag='boundaries-'+str(uuid.uuid4())
start=datetime.datetime.now(datetime.timezone.utc).replace(hour=0,minute=0,second=0,microsecond=0)-datetime.timedelta(days=2)
end=start+datetime.timedelta(days=3)
ids=[]
for index,ms in enumerate([-1,0,0,1,172800000,259200000]):
    timestamp=start+datetime.timedelta(milliseconds=ms)
    event=uuid_from_time(timestamp,node=index+1,clock_seq=1)
    batch=BatchStatement(consistency_level=ConsistencyLevel.ALL)
    for key in ['result','operation','entity']:
        batch.add('INSERT INTO business_events.by_'+key+' ('+key+'_key,day,event_id,result,operation,entity,http_status,duration_ms) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)',(tag,timestamp.date(),event,tag,tag,tag,200,0))
    s.execute(batch)
    if 0<=ms<259200000:ids.append(str(event))
print(json.dumps({'tag':tag,'from':start.isoformat(),'to':end.isoformat(),'ids':ids}));c.shutdown()
