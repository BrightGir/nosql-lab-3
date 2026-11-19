package ru.bright.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditController.class)
@org.springframework.context.annotation.Import(AuditCapture.class)
class AuditEndpointTest {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AuditQueryService service;
    @Test void missingQueryMustBeBadRequest() throws Exception {
        mvc.perform(get("/api/business-events")).andExpect(status().isBadRequest());
    }
}
