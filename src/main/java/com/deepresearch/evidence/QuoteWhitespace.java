package com.deepresearch.evidence;

/** Explicit Unicode White_Space set, shared verbatim with evidence_check.py. */
final class QuoteWhitespace {
    private QuoteWhitespace() { }
    static boolean space(int c) {
        return c >= 0x9 && c <= 0xd || c == 0x20 || c == 0x85 || c == 0xa0 || c == 0x1680
                || c >= 0x2000 && c <= 0x200a || c == 0x2028 || c == 0x2029 || c == 0x202f
                || c == 0x205f || c == 0x3000;
    }
    static boolean blank(String s) { return s.codePoints().allMatch(QuoteWhitespace::space); }
}
