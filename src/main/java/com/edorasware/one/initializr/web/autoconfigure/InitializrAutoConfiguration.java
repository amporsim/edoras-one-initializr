/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.edorasware.one.initializr.web.autoconfigure;

import com.edorasware.one.initializr.generator.*;
import com.edorasware.one.initializr.metadata.*;
import com.edorasware.one.initializr.util.TemplateRenderer;
import com.edorasware.one.initializr.web.controller.MainController;
import com.edorasware.one.initializr.web.support.DefaultDependencyMetadataProvider;
import com.edorasware.one.initializr.web.ui.UiController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.JCacheManagerCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.bind.RelaxedPropertyResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link org.springframework.boot.autoconfigure.EnableAutoConfiguration
 * Auto-configuration} to configure Spring initializr. In a web environment,
 * configures the necessary controller to serve the applications from the
 * root context.
 *
 * <p>Project generation can be customized by defining a custom
 * {@link ProjectGenerator}.
 *
 * @author Stephane Nicoll
 */
@Configuration
@EnableConfigurationProperties(InitializrProperties.class)
@AutoConfigureAfter(CacheAutoConfiguration.class)
public class InitializrAutoConfiguration {

	private final List<ProjectRequestPostProcessor> postProcessors;

	public InitializrAutoConfiguration(
			ObjectProvider<List<ProjectRequestPostProcessor>> postProcessors) {
		List<ProjectRequestPostProcessor> list = postProcessors.getIfAvailable();
		this.postProcessors = list != null ? list : new ArrayList<>();
	}

	@Bean
	public WebConfig webConfig() {
		return new WebConfig();
	}

	@Bean
	@ConditionalOnMissingBean
	public MainController initializrMainController(
			InitializrMetadataProvider metadataProvider,
			ResourceUrlProvider resourceUrlProvider,
			ProjectGenerator projectGenerator,
			AddonGenerator addonGenerator,
			DependencyMetadataProvider dependencyMetadataProvider) {
		return new MainController(metadataProvider, resourceUrlProvider
				, projectGenerator, addonGenerator, dependencyMetadataProvider);
	}

	@Bean
	@ConditionalOnMissingBean
	public UiController initializrUiController(
			InitializrMetadataProvider metadataProvider) {
		return new UiController(metadataProvider);
	}

	@Bean
	@ConditionalOnMissingBean
	public ProjectGenerator projectGenerator() {
		return new ProjectGenerator();
	}

	@Bean
	@ConditionalOnMissingBean
	public AddonGenerator addonGenerator() {
		return new AddonGenerator();
	}

	@Bean
	@ConditionalOnMissingBean
	public TemplateRenderer templateRenderer(Environment environment) {
		RelaxedPropertyResolver resolver = new RelaxedPropertyResolver(environment,
				"spring.mustache.");
		boolean cache = resolver.getProperty("cache", Boolean.class, true);
		TemplateRenderer templateRenderer = new TemplateRenderer();
		templateRenderer.setCache(cache);
		return templateRenderer;
	}

	@Bean
	@ConditionalOnMissingBean
	public ProjectRequestResolver projectRequestResolver() {
		return new ProjectRequestResolver(postProcessors);
	}

	@Bean
	public ProjectResourceLocator projectResourceLocator() {
		return new ProjectResourceLocator();
	}

	@Bean
	@ConditionalOnMissingBean(InitializrMetadataProvider.class)
	public InitializrMetadataProvider initializrMetadataProvider(
			InitializrProperties properties) {
//		InitializrMetadata metadata = InitializrMetadataBuilder
//				.fromInitializrProperties(properties).build();

		InitializrMetadata metadata = InitializrMetadataBuilder.create()
				.withInitializrMetadata(new ClassPathResource("metadata/defaults.json"))
				.build();

//		InitializrMetadata metadata = OfflineInitializrMetadataBuilder.withDefaults().build();
		return new SimpleInitializrMetadataProvider(metadata);

//		return new DefaultInitializrMetadataProvider(metadata, new RestTemplate());
	}

	@Bean
	@ConditionalOnMissingBean
	public DependencyMetadataProvider dependencyMetadataProvider() {
		return new DefaultDependencyMetadataProvider();
	}

	@Configuration
	@ConditionalOnClass(javax.cache.CacheManager.class)
	static class CacheConfiguration {

		@Bean
		public JCacheManagerCustomizer initializrCacheManagerCustomizer() {
			return cm -> {
				cm.createCache("initializr", config().setExpiryPolicyFactory(
						CreatedExpiryPolicy.factoryOf(Duration.TEN_MINUTES)));
				cm.createCache("dependency-metadata", config());
				cm.createCache("project-resources", config());
			};
		}

		private MutableConfiguration<Object, Object> config() {
			return new MutableConfiguration<>()
					.setStoreByValue(false)
					.setManagementEnabled(true).setStatisticsEnabled(true);
		}

	}

}
