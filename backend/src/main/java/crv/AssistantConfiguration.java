package crv;

import crv.service.AssistantProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the assistant's settings ({@code crv.assistant.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssistantProperties.class)
class AssistantConfiguration {}
