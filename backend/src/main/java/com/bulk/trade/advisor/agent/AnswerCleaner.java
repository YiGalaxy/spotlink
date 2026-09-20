package com.bulk.trade.advisor.agent;

import lombok.extern.slf4j.Slf4j;

/**
 * Removes a model's leaked working notes from the front of an answer.
 *
 * <p><b>Why this exists rather than a stronger instruction.</b> The system
 * prompt already says, at length, to output only the finished answer. It works
 * for short questions and fails for hard ones: asked to review a contract, a
 * reasoning model drafts a plan — "The caller is X, the seller. Now compose the
 * review. Contract review structure: 1. Basic info…" — and the draft reaches
 * the user. No wording fixes this reliably, because the failure is not
 * disobedience; it is that the model's scratchpad and its output share one
 * channel on the gateway this deployment talks to.
 *
 * <p>So the guard is deterministic code, and it has to be a heuristic. The one
 * that works here is the language: {@link #CJK_RATIO} of the answer is
 * guaranteed Chinese by rule 3 of the prompt, while planning happens in
 * English. A line that is mostly not Chinese, before any line that is, is
 * working notes.
 *
 * <p><b>Deliberately conservative.</b> Everything before the first
 * substantially-Chinese line is dropped, and nothing else is touched — no
 * middle-of-answer edits, no rewrites. A cleaner that reached further into the
 * text would eventually delete a sentence someone needed; this one can only
 * ever remove a prefix, and it logs each time it does, so the rate is visible
 * rather than a silent habit.
 */
@Slf4j
public final class AnswerCleaner {

    /**
     * Fraction of a line that must be Chinese for it to count as the answer.
     *
     * <p>Chosen from real output rather than taste. Leaked planning lines sit
     * near 0.1 — "The caller is 华东金属材料有限公司, the seller" is mostly English
     * with a company name embedded — while answer lines sit above 0.4, even
     * table rows, which carry a label per column. 0.35 separates them with room
     * on both sides.
     */
    private static final double CJK_RATIO = 0.35;

    /**
     * A line must carry at least this much Chinese to be considered the answer.
     *
     * <p>Guards the ratio against short lines: "OK。" is 100% Chinese and means
     * nothing, and a stray interjection must not be mistaken for the start of
     * the reply.
     */
    private static final int MIN_CJK_CHARS = 4;

    private AnswerCleaner() {
    }

    /**
     * Returns the answer with any leading working notes removed.
     *
     * @param raw whatever the model produced, possibly prefixed with planning
     * @return the answer; the input unchanged when it already reads as one; and
     *         <b>null</b> when nothing in it reads as an answer at all, so the
     *         caller can decide what to do about that — which is not this
     *         class's decision to make
     */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String[] lines = raw.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (isAnswerLine(lines[i])) {
                start = i;
                break;
            }
        }

        if (start < 0) {
            // Not one line anywhere reads as an answer. Two ways that happens,
            // and they are worth telling apart: the model answered in a
            // language the prompt forbids, or it produced only its scratchpad
            // and stopped. The second is the one seen in practice — a contract
            // review that returned fifteen hundred characters of English
            // planning and never reached a conclusion.
            //
            // Both mean there is no answer to show, so neither is returned.
            // Emptiness would be worse than an apology, and the raw text is
            // worse than both.
            log.warn("Advisor produced no answer line in {} characters; suppressed",
                    raw.length());
            // The text is logged in full, because the alternative is guessing
            // twice about what shape a suppressed answer had — which is exactly
            // what happened the first time this fired in earnest.
            log.warn("Suppressed advisor text:\n{}", raw);
            return null;
        }

        if (start == 0) {
            return raw;
        }

        String cleaned = String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length)).strip();
        log.info("Dropped {} line(s) of model working notes before the answer", start);
        log.debug("Dropped prefix began: {}", lines[0]);
        return cleaned;
    }

    /**
     * Whether a line reads as the start of the answer rather than as planning.
     *
     * <p>Blank lines are never the answer, so they never start it — which is
     * what lets the dropped prefix take the blank line between the notes and
     * the reply along with it, without a special case.
     *
     * <p><b>Two conditions, and the second was added after the first failed on
     * real output.</b> Chinese-ness alone was not enough: planning a contract
     * review, the model wrote "- 数量 20 吨，单价 68000 元/吨" as a note, and that
     * line is 47% Chinese — above any ratio that a genuine answer line would
     * also clear. What separates them is shape rather than language. A line
     * that <em>opens</em> a reply is a heading, a table row, or a sentence that
     * finishes its thought; a line of notes is a fragment that trails off into a
     * number or a unit.
     *
     * <p>So an answer line must additionally either carry Markdown structure
     * that a reader would recognise as the start of something, or end in the
     * punctuation a finished Chinese sentence ends in. Checked against every
     * line of a real leaked response and against ordinary answers.
     */
    private static boolean isAnswerLine(String line) {
        if (line.isBlank()) {
            return false;
        }

        int cjk = 0;
        int counted = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            counted++;
            if (isCjk(c)) {
                cjk++;
            }
        }
        if (counted == 0 || cjk < MIN_CJK_CHARS || (double) cjk / counted < CJK_RATIO) {
            return false;
        }

        String trimmed = line.strip();
        return STARTS_A_BLOCK.matcher(trimmed).find()
                || ENDS_A_SENTENCE.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
    }

    /** Markdown that opens something: a heading, a quote, a table row. */
    private static final java.util.regex.Pattern STARTS_A_BLOCK =
            java.util.regex.Pattern.compile("^(#{1,6}\\s|>|\\|)");

    /** Sentence-final Chinese punctuation. A fragment does not have one. */
    private static final String ENDS_A_SENTENCE = "。！？：；…";

    /**
     * Chinese, plus the full-width punctuation that comes with it.
     *
     * <p>Punctuation is counted as Chinese because it is a strong tell: leaked
     * English planning uses ASCII commas and periods even when it quotes a
     * Chinese name, so counting full-width marks as Chinese makes the two cases
     * separate more cleanly rather than less.
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // unified ideographs
                || (c >= 0x3000 && c <= 0x303F)  // CJK punctuation
                || (c >= 0xFF00 && c <= 0xFFEF); // full-width forms
    }
}
