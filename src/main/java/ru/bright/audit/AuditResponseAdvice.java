package ru.bright.audit;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import ru.bright.model.Event;
import ru.bright.model.DraftOrder;
@RestControllerAdvice
public class AuditResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet && "POST".equals(servlet.getServletRequest().getMethod())) {
            if (body instanceof Event event) servlet.getServletRequest().setAttribute("audit.entity",event.id());
            if (body instanceof DraftOrder draft) servlet.getServletRequest().setAttribute("audit.entity",draft.id());
        }
        return body;
    }
}
