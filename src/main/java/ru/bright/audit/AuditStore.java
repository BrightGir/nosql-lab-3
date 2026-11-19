package ru.bright.audit;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AuditStore {
    void append(BusinessEvent event);
    List<BusinessEvent> read(String dimension, String value, LocalDate day,
                            UUID lower, boolean lowerInclusive, UUID upper, boolean upperInclusive,
                            boolean ascending, int limit);
}
