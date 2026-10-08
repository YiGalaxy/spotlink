package com.spotlink.advisor.config;

import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** 每轮创建客户端，不修改全局密钥；补答继续使用本轮快照。 */
@Component
public class AdvisorModelClientFactory {
    public ChatClient create(AdvisorModelSettings settings) {
        if (!settings.enabled()) throw BusinessException.of(ResultCode.ADVISOR_DISABLED);
        if (!settings.configured()) throw BusinessException.of(ResultCode.ADVISOR_NOT_CONFIGURED);
        AdvisorModelSettingsService.validateUrl(settings.baseUrl());
        JdkClientHttpRequestFactory http = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        http.setReadTimeout(Duration.ofSeconds(settings.timeoutSeconds()));
        OpenAiApi api = OpenAiApi.builder().baseUrl(settings.baseUrl()).apiKey(settings.apiKey())
                .completionsPath(settings.completionsPath())
                .restClientBuilder(RestClient.builder().requestFactory(http))
                .responseErrorHandler(new SafeErrorHandler()).build();
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(settings.model());
        if ("max_completion_tokens".equals(settings.tokenParameter())) options.maxCompletionTokens(settings.maxTokens());
        else options.maxTokens(settings.maxTokens());
        return ChatClient.create(OpenAiChatModel.builder().openAiApi(api).defaultOptions(options.build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build());
    }

    /** 上游错误体可能回显请求或密钥，既不返回也不写入框架日志。 */
    private static class SafeErrorHandler extends DefaultResponseErrorHandler {
        @Override public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().value() >= 300;
        }
        @Override public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            throw new RestClientException("模型服务 HTTP " + response.getStatusCode().value());
        }
    }
}
