package me.taromati.almah.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * SPA 폴백 설정.
 * 정적 리소스가 아니고 API 경로도 아닌 요청은 index.html로 포워딩하여
 * Vue Router가 클라이언트 사이드 라우팅을 처리하도록 한다.
 *
 * 리소스 핸들러는 컨트롤러(@GetMapping)보다 후순위이므로
 * 기존 컨트롤러(@GetMapping)와 충돌하지 않는다.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaPathResourceResolver());
    }

    private static class SpaPathResourceResolver extends PathResourceResolver {

        private static final String[] API_PREFIXES = {
                "/api/", "/agent/api/", "/webhook/"
        };

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource resource = location.createRelative(resourcePath);
            if (resource.isReadable()) {
                return resource;
            }

            // API 경로는 폴백하지 않음 (404 또는 컨트롤러가 처리)
            String path = "/" + resourcePath;
            for (String prefix : API_PREFIXES) {
                if (path.startsWith(prefix)) {
                    return null;
                }
            }

            // SPA 폴백: index.html 반환
            Resource indexHtml = new ClassPathResource("/static/index.html");
            return indexHtml.isReadable() ? indexHtml : null;
        }
    }
}
