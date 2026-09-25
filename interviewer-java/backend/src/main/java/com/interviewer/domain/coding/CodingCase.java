package com.interviewer.domain.coding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;

/** 一条用例。走标准输入输出，不往用户代码里注入调用，判题只比字符串。 */
public record CodingCase(String input, String expected, String note) {

    @JsonCreator
    public CodingCase(@JsonProperty("input") String input,
                      @JsonProperty("expected") String expected,
                      @JsonProperty("note") String note) {
        this.input = Text.safe(input);
        this.expected = Text.safe(expected);
        this.note = Text.safe(note);
    }
}
