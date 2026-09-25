package com.interviewer.data.corpus;

/**
 * 语料里的一条真题。
 *
 * <p>只有分类、问题表述和跨源频次，不含任何来源信息。sources 是这道题在多少份面经里
 * 出现过，频次越高越是必考的基础概念。
 */
public record RealQuestion(String category, String text, int sources) {
}
