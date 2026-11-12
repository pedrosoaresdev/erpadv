package br.com.erpadv.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JustificationInterceptor justification;

    public WebConfig(JustificationInterceptor justification) {
        this.justification = justification;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(justification).addPathPatterns("/api/**");
    }
}
