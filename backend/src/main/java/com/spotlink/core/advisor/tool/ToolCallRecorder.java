package com.spotlink.advisor.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 收集一次顾问回合中发生的全部工具调用。
 *
 * <p><b>为什么需要它。</b>Spring AI 在内部跑完了整个工具调用循环，而它只返回最终答案：
 * 哪些工具跑过、带着什么参数、返回了什么，这一整条轨迹并不在响应里。而这条轨迹不是
 * 装饰——它让用户得以核对拿到的数字，也是答案出错时第一个要看的地方。所以它由
 * {@link ToolCallRecordingAspect} 单独采集，再挂回答案上。
 *
 * <p>作用域是线程，因为工具跑在触发它的那个请求的同一条线程上，而并发的会话绝不能看见
 * 彼此的工具调用。{@link #drain()} 会清空槽位，所以一条被复用的线程不可能把上一段
 * 会话的轨迹泄漏到下一段。
 */
public final class ToolCallRecorder {

    /** 一次工具调用，就是显示在回答下方的那条。 */
    public record Invocation(String name, String input, String output) {
    }

    private static final ThreadLocal<List<Invocation>> CURRENT = new ThreadLocal<>();

    private ToolCallRecorder() {
    }

    /** 开一份新的收集，之前留下的东西一并丢掉。 */
    public static void begin() {
        CURRENT.set(new ArrayList<>());
    }

    public static void record(String name, String input, String output) {
        List<Invocation> invocations = CURRENT.get();
        if (invocations == null) {
            // 在一次回合之外记录，意味着某个工具在没有会话包围的情况下跑了；
            // 那条轨迹没有东西可以挂。
            return;
        }
        invocations.add(new Invocation(name, summarize(input), summarize(output)));
    }

    /** 返回已收集的内容并清空槽位。**总要调用它。** */
    public static List<Invocation> drain() {
        List<Invocation> invocations = CURRENT.get();
        CURRENT.remove();
        return invocations == null ? List.of() : List.copyOf(invocations);
    }

    /**
     * 给单条记录设上界，这样一个失控的返回值不会把存下来的对话撑爆。
     * 完整值在被展示过一次之后就无关紧要了。
     */
    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        int limit = 4000;
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
