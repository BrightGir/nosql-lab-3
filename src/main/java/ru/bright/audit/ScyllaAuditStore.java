package ru.bright.audit;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.bright.exception.StorageUnavailableException;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name="app.scylla.enabled", havingValue="true")
public class ScyllaAuditStore implements AuditStore {
    private final CqlSession session;
    private final Map<String, PreparedStatement> prepared = new ConcurrentHashMap<>();
    public ScyllaAuditStore(@Value("${app.scylla.contact-points}") String points) {
        var builder = CqlSession.builder().withLocalDatacenter("datacenter1")
                .withConfigLoader(DriverConfigLoader.programmaticBuilder()
                        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(3)).build());
        for (String point : points.split(",")) {
            String[] parts = point.trim().split(":");
            builder.addContactPoint(new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
        }
        session = builder.build();
    }
    private PreparedStatement prepare(String cql) { return prepared.computeIfAbsent(cql, session::prepare); }
    @Override public void append(BusinessEvent event) {
        try {
            var batch = BatchStatement.builder(DefaultBatchType.LOGGED)
                    .setConsistencyLevel(DefaultConsistencyLevel.ONE).setIdempotence(true);
            Map<String,String> keys = Map.of("result",event.result(),"operation",event.operation(),"entity",event.entity());
            for (var key : keys.entrySet()) {
                batch.addStatement(prepare("INSERT INTO business_events.by_"+key.getKey()+" ("+key.getKey()+
                        "_key,day,event_id,result,operation,entity,http_status,duration_ms) VALUES (?,?,?,?,?,?,?,?)")
                        .bind(key.getValue(),event.day(),event.id(),event.result(),event.operation(),event.entity(),event.httpStatus(),event.durationMs()));
            }
            session.execute(batch.build());
        } catch (RuntimeException e) { throw new StorageUnavailableException("ScyllaDB event write failed", e); }
    }
    @Override public List<BusinessEvent> read(String dimension, String value, LocalDate day,
            UUID lower, boolean lowerInclusive, UUID upper, boolean upperInclusive, boolean ascending, int limit) {
        if (!Set.of("result","operation","entity").contains(dimension)) throw new IllegalArgumentException("Invalid dimension");
        String cql = "SELECT event_id,result,operation,entity,http_status,duration_ms FROM business_events.by_"+dimension+
                " WHERE "+dimension+"_key=? AND day=? AND event_id "+(lowerInclusive?">=":">")+" ? AND event_id "+
                (upperInclusive?"<=":"<")+" ? ORDER BY event_id "+(ascending?"ASC":"DESC")+" LIMIT ?";
        try {
            return session.execute(prepare(cql).bind(value,day,lower,upper,limit)
                    .setConsistencyLevel(DefaultConsistencyLevel.QUORUM).setPageSize(limit))
                    .all().stream().map(r -> new BusinessEvent(r.getUuid("event_id"),r.getString("result"),
                            r.getString("operation"),r.getString("entity"),r.getInt("http_status"),r.getLong("duration_ms"))).toList();
        } catch (RuntimeException e) { throw new StorageUnavailableException("ScyllaDB event read failed", e); }
    }
    @PreDestroy public void close() { session.close(); }
}
