package ru.bright.audit;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class AuditWebConfiguration implements WebMvcConfigurer {
    private final AuditCapture capture;
    public AuditWebConfiguration(AuditCapture capture) { this.capture=capture; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(capture).addPathPatterns("/api/**"); }
}
