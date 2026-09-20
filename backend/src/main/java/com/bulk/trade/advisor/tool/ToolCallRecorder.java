package com.bulk.trade.advisor.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the tool calls made during one advisor turn.
 *
 * <p><b>Why this exists.</b> Spring AI runs the tool-calling loop internally and
 * returns only the final answer — the trail of which tools ran, with what
 * arguments, returning what, is not part of the response. That trail is not
 * decoration: it is what lets a user check the figure they were given, and it
 * is the first thing to look at when an answer is wrong. So it is captured
 * separately, by {@link ToolCallRecordingAspect}, and re-attached to the answer.
 *
 * <p>Thread-scoped because a tool runs on the same thread as the request that
 * triggered it, and concurrent conversations must not see each other's calls.
 * {@link #drain()} clears the slot, so a pooled thread cannot leak a previous
 * conversation's trail into the next one.
 */
public final class ToolCallRecorder {

    /** One tool invocation, as shown under an answer. */
    public record Invocation(String name, String input, String output) {
    }

    private static final ThreadLocal<List<Invocation>> CURRENT = new ThreadLocal<>();

    private ToolCallRecorder() {
    }

    /** Starts a fresh collection, discarding anything left behind. */
    public static void begin() {
        CURRENT.set(new ArrayList<>());
    }

    public static void record(String name, String input, String output) {
        List<Invocation> invocations = CURRENT.get();
        if (invocations == null) {
            // Recording outside a turn would mean a tool ran without a
            // conversation around it; nothing to attach the trail to.
            return;
        }
        invocations.add(new Invocation(name, summarize(input), summarize(output)));
    }

    /** Returns what was collected and clears the slot. Always call this. */
    public static List<Invocation> drain() {
        List<Invocation> invocations = CURRENT.get();
        CURRENT.remove();
        return invocations == null ? List.of() : List.copyOf(invocations);
    }

    /**
     * Caps one entry so a runaway result cannot bloat the stored transcript.
     * The full value is irrelevant once it has been shown once.
     */
    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        int limit = 4000;
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
