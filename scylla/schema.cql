CREATE KEYSPACE IF NOT EXISTS business_events WITH replication = {'class': 'NetworkTopologyStrategy', 'datacenter1': 3};

CREATE TABLE IF NOT EXISTS business_events.by_result (
    result_key text, day date, event_id timeuuid,
    result text, operation text, entity text, http_status int, duration_ms bigint,
    PRIMARY KEY ((result_key, day), event_id)
) WITH CLUSTERING ORDER BY (event_id DESC)
  AND gc_grace_seconds = 864000
  AND tombstone_gc = {'mode': 'timeout'};

CREATE TABLE IF NOT EXISTS business_events.by_operation (
    operation_key text, day date, event_id timeuuid,
    result text, operation text, entity text, http_status int, duration_ms bigint,
    PRIMARY KEY ((operation_key, day), event_id)
) WITH CLUSTERING ORDER BY (event_id DESC)
  AND gc_grace_seconds = 864000
  AND tombstone_gc = {'mode': 'timeout'};

CREATE TABLE IF NOT EXISTS business_events.by_entity (
    entity_key text, day date, event_id timeuuid,
    result text, operation text, entity text, http_status int, duration_ms bigint,
    PRIMARY KEY ((entity_key, day), event_id)
) WITH CLUSTERING ORDER BY (event_id DESC)
  AND gc_grace_seconds = 864000
  AND tombstone_gc = {'mode': 'timeout'};
