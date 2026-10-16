package com.greenharborlabs.jevlab.jev;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class JevConfiguration {

  @Bean
  JevClient jevClient(JevProperties properties) {
    return create(properties);
  }

  /** Builds a client on the JDK HTTP client with the configured timeouts. */
  public static JevClient create(JevProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return new JevClient(
        RestClient.builder().requestFactory(requestFactory),
        properties,
        duration -> Thread.sleep(duration));
  }
}
