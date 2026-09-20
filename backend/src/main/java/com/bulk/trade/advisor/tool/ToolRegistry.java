package com.bulk.trade.advisor.tool;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Holds every {@link AdvisorTool} in the application context.
 *
 * <p>Tools are indexed in a {@link LinkedHashMap} sorted by name. Order matters
 * beyond tidiness: the tool list is part of the prompt-cache prefix, and the
 * cache matches on bytes. An unstable iteration order would silently invalidate
 * the cache on every request and multiply the input bill.
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AdvisorTool> tools;

    public ToolRegistry(List<AdvisorTool> discovered) {
        this.tools = discovered.stream()
                .sorted(Comparator.comparing(AdvisorTool::name))
                .collect(Collectors.toMap(
                        AdvisorTool::name,
                        Function.identity(),
                        (existing, duplicate) -> {
                            throw new IllegalStateException(
                                    "Duplicate advisor tool name: " + existing.name());
                        },
                        LinkedHashMap::new));
        log.info("Advisor tools registered ({}): {}", tools.size(), tools.keySet());
    }

    /** Tools in stable name order. */
    public List<AdvisorTool> all() {
        return List.copyOf(tools.values());
    }

    public AdvisorTool find(String name) {
        return tools.get(name);
    }

    /**
     * Runs a tool and converts any failure into an error sentence.
     *
     * <p>A thrown exception would abort the whole agent loop; an error string
     * goes back to the model as an ordinary tool result, letting it correct its
     * own mistake or explain the problem to the user.
     */
    public String execute(String name, JsonNode input, AdvisorContext context) {
        AdvisorTool tool = tools.get(name);
        if (tool == null) {
            log.warn("Model requested an unregistered tool: {}", name);
            return "Error: no tool named '" + name + "' exists. Available tools: " + tools.keySet();
        }
        try {
            long started = System.currentTimeMillis();
            String result = tool.execute(input, context);
            log.debug("Tool '{}' completed in {} ms", name, System.currentTimeMillis() - started);
            return result;
        } catch (Exception e) {
            log.error("Tool '{}' failed", name, e);
            return "Error: tool '" + name + "' failed with " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
        }
    }
}
