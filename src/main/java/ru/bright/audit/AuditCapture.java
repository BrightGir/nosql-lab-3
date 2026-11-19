package ru.bright.audit;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AuditCapture implements HandlerInterceptor {
    private static final Logger log=LoggerFactory.getLogger(AuditCapture.class);
    private static final String START=AuditCapture.class.getName()+".start";
    private final ObjectProvider<AuditStore> stores;
    private final AtomicLong recorded=new AtomicLong(), failed=new AtomicLong();
    public AuditCapture(ObjectProvider<AuditStore> stores) { this.stores=stores; }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        request.setAttribute(START,System.nanoTime()); return true;
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception exception) {
        String pattern=String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        String operation=switch (request.getMethod()+" "+pattern) {
            case "POST /api/events" -> "CREATE_EVENT";
            case "PUT /api/events/{id}" -> "UPDATE_EVENT";
            case "DELETE /api/events/{id}" -> "DELETE_EVENT";
            case "POST /api/events/{id}/views" -> "REGISTER_VIEW";
            case "POST /api/drafts" -> "CREATE_DRAFT";
            case "POST /api/drafts/{id}/submit" -> "SUBMIT_DRAFT";
            case "PUT /api/managers/{managerId}/settings" -> "SAVE_SETTINGS";
            default -> null;
        };
        if (operation==null) return;
        AuditStore store=stores.getIfAvailable();
        if (store==null) return;
        Object variables=request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String entity="collection";
        if (variables instanceof Map<?,?> map) {
            Object id=map.get("id"); if (id==null) id=map.get("managerId");
            if (id!=null) entity=id.toString();
        }
        // Entity IDs for create responses are supplied by the response advice.
        if (request.getAttribute("audit.entity")!=null) entity=request.getAttribute("audit.entity").toString();
        Object start=request.getAttribute(START);
        long duration=start instanceof Long n?(System.nanoTime()-n)/1_000_000:0;
        int status=exception!=null?500:response.getStatus();
        BusinessEvent event=new BusinessEvent(Uuids.timeBased(),status<400?"SUCCESS":"FAILURE",operation,entity,status,duration);
        try { store.append(event); recorded.incrementAndGet(); }
        catch (RuntimeException e) { failed.incrementAndGet(); log.error("Business audit delivery failed: id={} operation={} entity={}",event.id(),operation,entity,e); }
    }
    public Map<String,Object> status() { return Map.of("enabled",stores.getIfAvailable()!=null,"recorded",recorded.get(),"failed",failed.get(),"writeConsistency","ONE","readConsistency","QUORUM"); }
}
