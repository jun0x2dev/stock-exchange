package com.exchange.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 화면 주소로 바로 들어와도 화면이 뜨도록 index.html로 넘긴다. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		for (String page : SecurityConfig.PAGES) {
			registry.addViewController(page).setViewName("forward:/index.html");
		}
	}
}
