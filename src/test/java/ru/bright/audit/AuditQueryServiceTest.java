package ru.bright.audit;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuditQueryServiceTest {
    private static final Instant FROM=Instant.parse("2026-11-10T00:00:00Z"), TO=FROM.plusSeconds(3*86400);
    private final List<BusinessEvent> data=new ArrayList<>();
    private final AuditStore store=new AuditStore() {
        public void append(BusinessEvent e) { data.add(e); }
        public List<BusinessEvent> read(String dimension,String value,LocalDate day,UUID lower,boolean li,UUID upper,boolean ui,boolean asc,int limit) {
            Comparator<BusinessEvent> order=Comparator.comparingLong(e->e.id().timestamp());
            return data.stream().filter(e->e.day().equals(day))
                .filter(e->(li?e.id().timestamp()>=lower.timestamp():e.id().timestamp()>lower.timestamp())
                    && (ui?e.id().timestamp()<=upper.timestamp():e.id().timestamp()<upper.timestamp()))
                .sorted(asc?order:order.reversed()).limit(limit).toList();
        }
    };
    private AuditQueryService service() {
        var beans=new StaticListableBeanFactory(); beans.addBean("store",store);
        return new AuditQueryService(beans.getBeanProvider(AuditStore.class),new ObjectMapper().findAndRegisterModules());
    }
    private void seed(long... millis) {
        for (long ms:millis) store.append(new BusinessEvent(Uuids.startOf(FROM.toEpochMilli()+ms),"SUCCESS","TEST","entity",200,1));
    }
    @Test void pagesAcrossEmptyDaysInBothDirectionsAndExcludesUpperBoundary() {
        seed(0,1,2,2*86400000L,3*86400000L);
        for (String sort:List.of("asc","desc")) {
            String cursor=null; List<UUID> ids=new ArrayList<>(); int pages=0;
            do {
                var page=service().query("SUCCESS",null,null,FROM,TO,sort,2,cursor);
                ids.addAll(page.events().stream().map(BusinessEvent::id).toList());
                cursor=page.nextCursor(); assertThat(++pages).isLessThan(4);
            } while(cursor!=null);
            var expected=new ArrayList<>(data.subList(0,4).stream().map(BusinessEvent::id).toList());
            if(sort.equals("desc")) Collections.reverse(expected);
            assertThat(ids).containsExactlyElementsOf(expected);
        }
    }
    @Test void cursorCannotBeReusedWithDifferentFiltersOrDirection() {
        seed(0,1);
        String cursor=service().query("SUCCESS",null,null,FROM,TO,"asc",1,null).nextCursor();
        assertThatThrownBy(()->service().query("FAILURE",null,null,FROM,TO,"asc",1,cursor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service().query("SUCCESS",null,null,FROM,TO,"desc",1,cursor)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnboundedAmbiguousAndMalformedRequests() {
        assertThatThrownBy(()->service().query("SUCCESS","TEST",null,FROM,TO,"asc",1,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service().query("SUCCESS",null,null,TO,FROM,"asc",1,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service().query("SUCCESS",null,null,FROM,FROM.plusSeconds(32*86400),"asc",1,null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service().query("SUCCESS",null,null,FROM,TO,"asc",1,"garbage")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service().query("SUCCESS",null,null,FROM,TO,"asc",201,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
