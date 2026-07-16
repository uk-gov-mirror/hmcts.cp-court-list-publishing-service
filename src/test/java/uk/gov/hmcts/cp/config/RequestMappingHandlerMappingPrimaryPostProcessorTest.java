package uk.gov.hmcts.cp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Reproduces the actuator + cp-auth-rules-filter ambiguity: two RequestMappingHandlerMapping beans
 * make an ObjectProvider lookup throw, and verifies the post-processor resolves it by marking the
 * MVC handler mapping primary.
 */
class RequestMappingHandlerMappingPrimaryPostProcessorTest {

    private static final String MVC = "requestMappingHandlerMapping";
    private static final String ACTUATOR = "controllerEndpointHandlerMapping";

    /** Skips the handler-method scan so the bean can be instantiated in a bare bean factory. */
    static class NoOpHandlerMapping extends RequestMappingHandlerMapping {
        @Override
        public void afterPropertiesSet() {
            // no-op: avoid requiring an ApplicationContext
        }
    }

    private static DefaultListableBeanFactory beanFactoryWithTwoHandlerMappings() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition(MVC, new RootBeanDefinition(NoOpHandlerMapping.class));
        bf.registerBeanDefinition(ACTUATOR, new RootBeanDefinition(NoOpHandlerMapping.class));
        return bf;
    }

    @Test
    void marksMvcHandlerMappingPrimaryAndLeavesActuatorAlone() {
        DefaultListableBeanFactory bf = beanFactoryWithTwoHandlerMappings();

        new RequestMappingHandlerMappingPrimaryPostProcessor().postProcessBeanFactory(bf);

        assertThat(bf.getBeanDefinition(MVC).isPrimary()).isTrue();
        assertThat(bf.getBeanDefinition(ACTUATOR).isPrimary()).isFalse();
    }

    @Test
    void withoutPostProcessor_providerLookupIsAmbiguous() {
        DefaultListableBeanFactory bf = beanFactoryWithTwoHandlerMappings();

        // This is exactly what SpringTemplatedUrlFallback's factory does (ObjectProvider.getIfAvailable()).
        assertThatThrownBy(() -> bf.getBeanProvider(RequestMappingHandlerMapping.class).getIfAvailable())
                .isInstanceOf(NoUniqueBeanDefinitionException.class);
    }

    @Test
    void withPostProcessor_providerLookupResolvesToMvcBean() {
        DefaultListableBeanFactory bf = beanFactoryWithTwoHandlerMappings();

        new RequestMappingHandlerMappingPrimaryPostProcessor().postProcessBeanFactory(bf);

        RequestMappingHandlerMapping resolved = bf.getBeanProvider(RequestMappingHandlerMapping.class).getIfAvailable();
        assertThat(resolved).isSameAs(bf.getBean(MVC, RequestMappingHandlerMapping.class));
    }

    @Test
    void isNoOpWhenMvcHandlerMappingAbsent() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();

        // Should not throw when the bean is not present.
        new RequestMappingHandlerMappingPrimaryPostProcessor().postProcessBeanFactory(bf);

        assertThat(bf.containsBeanDefinition(MVC)).isFalse();
    }
}
