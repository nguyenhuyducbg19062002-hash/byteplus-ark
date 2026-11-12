package ark.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${app.cors.allowed-origins}")
    private String[] origins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Dùng pattern để chấp nhận mọi cổng localhost (Vite có thể tự nhảy sang 5174, 5175...)
        registry.addMapping("/api/**").allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }
}
