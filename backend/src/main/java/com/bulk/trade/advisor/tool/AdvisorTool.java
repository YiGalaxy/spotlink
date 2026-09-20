package com.bulk.trade.advisor.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * A capability the advisor can invoke.
 *
 * <p>Two rules every implementation must follow:
 *
 * <ol>
 *   <li><b>Tenant id is never an input parameter.</b> Read it from the
 *       {@link AdvisorContext}. If the model could pass an enterprise id, a
 *       prompt-injection in a user message or in retrieved content could make
 *       it read another company's data.</li>
 *   <li><b>Return a compact string.</b> Tool results are fed back into the
 *       model's context and billed as input tokens. Returning a full entity
 *       graph wastes money and crowds out the actual question.</li>
 * </ol>
 */
public interface AdvisorTool {

    /** Unique snake_case identifier the model calls, e.g. {@code query_enterprise_info}. */
    String name();

    /**
     * Tells the model when to use this tool and, just as importantly, when not
     * to. Written for the model, not for a human reader.
     */
    String description();

    /** JSON Schema for the arguments. Must not contain a tenant identifier. */
    Map<String, Object> inputSchema();

    /**
     * Executes the call.
     *
     * @param input   arguments as produced by the model
     * @param context caller identity and tenant scope
     * @return a short text result; return a plain error sentence rather than
     *         throwing — a thrown exception aborts the whole agent loop, while
     *         an error string lets the model try a different approach
     */
    String execute(JsonNode input, AdvisorContext context);

    /** Roles allowed to use this tool. Empty means any authenticated caller. */
    default java.util.Set<String> allowedRoles() {
        return java.util.Set.of();
    }
}
