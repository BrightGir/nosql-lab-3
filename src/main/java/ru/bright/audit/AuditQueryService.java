package ru.bright.audit;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.bright.exception.StorageUnavailableException;
import java.time.*;
import java.util.*;

@Service
public class AuditQueryService {
    private final ObjectProvider<AuditStore> stores;
    private final ObjectMapper json;
    public AuditQueryService(ObjectProvider<AuditStore> stores, ObjectMapper json) { this.stores=stores; this.json=json; }
    public record Page(List<BusinessEvent> events, String nextCursor) {}
    public record Cursor(String dimension, String value, Instant from, Instant to, String sort, UUID last) {}

    public Page query(String result, String operation, String entity, Instant from, Instant to, String sort, int limit, String cursor) {
        if ((result==null?0:1)+(operation==null?0:1)+(entity==null?0:1)!=1)
            throw new IllegalArgumentException("Exactly one of result, operation, entity is required");
        String dimension=result!=null?"result":operation!=null?"operation":"entity";
        String value=result!=null?result:operation!=null?operation:entity;
        if (value.isBlank() || value.length()>200) throw new IllegalArgumentException("Filter must contain 1..200 characters");
        if (from==null || to==null || !from.isBefore(to) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0)
            throw new IllegalArgumentException("Use a nonempty [from,to) period of at most 31 days");
        if (from.getNano()%1_000_000!=0 || to.getNano()%1_000_000!=0)
            throw new IllegalArgumentException("Time boundaries support millisecond precision");
        if (!Set.of("asc","desc").contains(sort) || limit<1 || limit>200)
            throw new IllegalArgumentException("sort must be asc|desc and limit 1..200");
        UUID last=null;
        if (cursor!=null) {
            try {
                if (cursor.length()>4096) throw new IllegalArgumentException();
                Cursor decoded=json.readValue(Base64.getUrlDecoder().decode(cursor),Cursor.class);
                if (!Objects.equals(decoded.dimension,dimension) || !Objects.equals(decoded.value,value)
                        || !Objects.equals(decoded.from,from) || !Objects.equals(decoded.to,to) || !Objects.equals(decoded.sort,sort)
                        || decoded.last==null || decoded.last.version()!=1) throw new IllegalArgumentException();
                last=decoded.last;
                Instant time=Instant.ofEpochMilli(Uuids.unixTimestamp(last));
                if (time.isBefore(from) || !time.isBefore(to)) throw new IllegalArgumentException();
            } catch (Exception e) { throw new IllegalArgumentException("Invalid cursor or changed query"); }
        }
        AuditStore store=stores.getIfAvailable();
        if (store==null) throw new StorageUnavailableException("ScyllaDB is disabled; set SCYLLA_ENABLED=true",null);
        boolean asc=sort.equals("asc");
        LocalDate first=from.atZone(ZoneOffset.UTC).toLocalDate(), end=to.minusMillis(1).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate day=last==null?(asc?first:end):Instant.ofEpochMilli(Uuids.unixTimestamp(last)).atZone(ZoneOffset.UTC).toLocalDate();
        List<BusinessEvent> found=new ArrayList<>();
        while (!day.isBefore(first) && !day.isAfter(end) && found.size()<=limit) {
            long start=Math.max(from.toEpochMilli(),day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
            long finish=Math.min(to.toEpochMilli(),day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
            UUID lower=last!=null && asc?last:Uuids.startOf(start);
            UUID upper=last!=null && !asc?last:Uuids.startOf(finish);
            found.addAll(store.read(dimension,value,day,lower,last==null || !asc,upper,false,asc,limit+1-found.size()));
            last=null; day=day.plusDays(asc?1:-1);
        }
        String next=null;
        if (found.size()>limit) {
            found=new ArrayList<>(found.subList(0,limit));
            try { next=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(
                    new Cursor(dimension,value,from,to,sort,found.get(found.size()-1).id()))); }
            catch (Exception e) { throw new IllegalStateException("Cursor encoding failed",e); }
        }
        return new Page(found,next);
    }
}
