package ru.bright.audit;

import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/business-events")
public class AuditController {
    private final AuditQueryService service;
    private final AuditCapture capture;
    public AuditController(AuditQueryService service, AuditCapture capture) { this.service=service; this.capture=capture; }
    @GetMapping
    public AuditQueryService.Page query(@RequestParam(required=false) String result,
            @RequestParam(required=false) String operation, @RequestParam(required=false) String entity,
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue="desc") String sort, @RequestParam(defaultValue="50") int limit,
            @RequestParam(required=false) String cursor) {
        return service.query(result,operation,entity,from,to,sort,limit,cursor);
    }
    @GetMapping("/status") public Map<String,Object> status() { return capture.status(); }
}
