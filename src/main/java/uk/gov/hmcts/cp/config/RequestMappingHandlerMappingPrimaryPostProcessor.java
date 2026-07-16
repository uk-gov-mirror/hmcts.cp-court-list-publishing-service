package uk.gov.hmcts.cp.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * TODO: delete this after auth filtyer 1.0.10 is released
 * Marks the Spring MVC {@code requestMappingHandlerMapping} bean as primary.
 *
 * <p>The {@code cp-auth-rules-filter} (from 1.0.9) auto-configures a {@code SpringTemplatedUrlFallback}
 * bean whose factory resolves a single
 * {@link org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping} via
 * {@code ObjectProvider.getIfAvailable()}. When Spring Boot Actuator is on the classpath there are two
 * beans assignable to that type — the MVC {@code requestMappingHandlerMapping} and the actuator
 * {@code controllerEndpointHandlerMapping} — so {@code getIfAvailable()} throws
 * {@code NoUniqueBeanDefinitionException} and the application fails to start whenever authz is enabled
 * (e.g. deployed environments). It is not caught in tests because authz is disabled there.
 *
 * <p>Marking the MVC handler mapping primary (the correct one for route-template resolution) removes the
 * ambiguity without replacing the auto-configured bean.
 */
@Component
public class RequestMappingHandlerMappingPrimaryPostProcessor implements BeanFactoryPostProcessor {

    static final String MVC_HANDLER_MAPPING_BEAN = "requestMappingHandlerMapping";

    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (beanFactory.containsBeanDefinition(MVC_HANDLER_MAPPING_BEAN)) {
            beanFactory.getBeanDefinition(MVC_HANDLER_MAPPING_BEAN).setPrimary(true);
        }
    }
}
