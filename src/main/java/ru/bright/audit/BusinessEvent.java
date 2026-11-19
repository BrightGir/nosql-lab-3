package ru.bright.audit;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

public record BusinessEvent(UUID id, String result, String operation, String entity,
                            int httpStatus, long durationMs) {
    public Instant getOccurredAt() { return Instant.ofEpochMilli(Uuids.unixTimestamp(id)); }
    public LocalDate day() { return getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate(); }
}
