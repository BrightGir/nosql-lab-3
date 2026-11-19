package ru.bright.audit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.*;
import org.springframework.web.servlet.HandlerMapping;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AuditCaptureTest {
    private final List<BusinessEvent> events=new ArrayList<>();
    private AuditCapture capture(boolean fail) {
        var beans=new StaticListableBeanFactory();
        beans.addBean("store", new AuditStore() {
            public void append(BusinessEvent event) { if(fail) throw new IllegalStateException("offline"); events.add(event); }
            public List<BusinessEvent> read(String d,String v,LocalDate day,UUID lo,boolean li,UUID hi,boolean ui,boolean asc,int limit) { throw new UnsupportedOperationException(); }
        });
        return new AuditCapture(beans.getBeanProvider(AuditStore.class));
    }
    private MockHttpServletRequest request(String method,String path) {
        var request=new MockHttpServletRequest(method,path);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,path);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,Map.of("id","draft-1"));
        return request;
    }
    @Test void recordsFailedAndSuccessfulSubmissionAttemptsWithEntity() {
        var capture=capture(false); var response=new MockHttpServletResponse();
        var request=request("POST","/api/drafts/{id}/submit");
        for(int code:List.of(201,409)) {
            response.setStatus(code); capture.preHandle(request,response,null); capture.afterCompletion(request,response,null,null);
        }
        assertThat(events).extracting(BusinessEvent::result).containsExactly("SUCCESS","FAILURE");
        assertThat(events).allMatch(e->e.operation().equals("SUBMIT_DRAFT") && e.entity().equals("draft-1") && e.id().version()==1);
        assertThat(events.get(0).id()).isNotEqualTo(events.get(1).id());
    }
    @Test void auditFailureDoesNotChangeCommittedBusinessResponse() {
        var capture=capture(true); var request=request("POST","/api/drafts/{id}/submit");
        var response=new MockHttpServletResponse(); response.setStatus(201);
        capture.preHandle(request,response,null);
        assertThatCode(()->capture.afterCompletion(request,response,null,null)).doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(capture.status().get("failed")).isEqualTo(1L);
    }
    @Test void readsDoNotCreateBusinessEvents() {
        var capture=capture(false); var request=request("GET","/api/events"); var response=new MockHttpServletResponse();
        capture.preHandle(request,response,null); capture.afterCompletion(request,response,null,null);
        assertThat(events).isEmpty();
    }
}
