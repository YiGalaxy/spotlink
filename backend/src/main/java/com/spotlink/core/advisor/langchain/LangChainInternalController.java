package com.spotlink.advisor.langchain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 不接受登录令牌，网关逐次验证专用短期委托；响应不包 ApiResponse。 */
@RestController
@RequestMapping("/internal/advisor/langchain")
@RequiredArgsConstructor
public class LangChainInternalController {
    private final LangChainGateway gateway;
    public record ToolRequest(String name, JsonNode arguments) {}
    @GetMapping("/key")
    public Map<String, String> key() { return gateway.publicKey(); }
    @PostMapping("/tools")
    public LangChainGateway.ToolReply tool(@RequestHeader(value = "Authorization", required = false) String bearer,
                                          @RequestBody ToolRequest request) {
        return gateway.callTool(bearer, request.name(), request.arguments());
    }
    @PostMapping("/v1/chat/completions")
    public JsonNode complete(@RequestHeader(value = "Authorization", required = false) String bearer, @RequestBody JsonNode body) {
        return gateway.complete(bearer, body);
    }
}
