package com.interviewer.domain.coding;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewer.core.Text;
import java.util.List;
import java.util.Map;

/**
 * 一道完整编码题。
 *
 * <p>与题库的口述题分开：那边只有题干，这里要能判题——输入输出格式、起始代码、
 * 用例、参考答案缺一样前端就没法跑。
 */
public record CodingChallenge(String title, String statement, String ioFormat,
                              Map<String, String> starter, Map<String, String> reference,
                              List<CodingCase> cases, List<String> hints) {

    @JsonCreator
    public CodingChallenge(@JsonProperty("title") String title,
                           @JsonProperty("statement") String statement,
                           @JsonProperty("io_format") String ioFormat,
                           @JsonProperty("starter") Map<String, String> starter,
                           @JsonProperty("reference") Map<String, String> reference,
                           @JsonProperty("cases") List<CodingCase> cases,
                           @JsonProperty("hints") List<String> hints) {
        this.title = Text.safe(title);
        this.statement = Text.safe(statement);
        this.ioFormat = Text.safe(ioFormat);
        this.starter = starter == null ? Map.of() : Map.copyOf(starter);
        this.reference = reference == null ? Map.of() : Map.copyOf(reference);
        this.cases = cases == null ? List.of() : List.copyOf(cases);
        this.hints = hints == null ? List.of() : List.copyOf(hints);
    }

    @JsonIgnore
    public String starterFor(String language) {
        return starter.getOrDefault(language, "");
    }

    @JsonIgnore
    public String referenceFor(String language) {
        return reference.getOrDefault(language, "");
    }
}
