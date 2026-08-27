package com.interviewer.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.Parity;
import com.interviewer.core.Text;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 宽松解析与 pydantic 的 LooseModel 端到端比对。
 *
 * <p>{@link LooseJson} 不是逐行翻译，是按同一份语义重写的，所以必须拿 Python 侧同构的
 * 模型跑同样的脏输入来验证。覆盖：别名回退、字符串取数、对象压成文本、字符串折成对象、
 * 装箱类型的 null 保留、缺省字段。
 *
 * <p>顺带钉住一条约定：record 的规范构造器必须把 null 归一成默认值。Java record 没有
 * 默认值语法，缺省字段 Jackson 会给 null，而 pydantic 给的是声明的默认值。
 */
class LooseJsonParityTest {

    record Inner(@JsonProperty("name") @JsonAlias({"title"}) String name,
                 @JsonProperty("score") double score) {

        Inner(String name, double score) {
            this.name = Text.safe(name);
            this.score = score;
        }
    }

    record Outer(@JsonProperty("skill") @JsonAlias({"requirement", "name"}) String skill,
                 @JsonProperty("count") int count,
                 @JsonProperty("ratio") double ratio,
                 @JsonProperty("flag") boolean flag,
                 @JsonProperty("opt_id") Integer optId,
                 @JsonProperty("tags") List<String> tags,
                 @JsonProperty("items") List<Inner> items,
                 @JsonProperty("note") String note) {

        Outer(String skill, int count, double ratio, boolean flag, Integer optId,
              List<String> tags, List<Inner> items, String note) {
            this.skill = Text.safe(skill);
            this.count = count;
            this.ratio = ratio;
            this.flag = flag;
            this.optId = optId;
            this.tags = tags == null ? List.of() : List.copyOf(tags);
            this.items = items == null ? List.of() : List.copyOf(items);
            this.note = Text.safe(note);
        }
    }

    @Test
    void parseMatchesPydanticLooseModel() throws Exception {
        JsonNode samples = Parity.load("loose");
        Assumptions.assumeTrue(samples != null, "没有 loose 基准文件");

        ObjectMapper mapper = new ObjectMapper();
        LooseJson loose = new LooseJson(mapper);

        for (JsonNode sample : samples) {
            JsonNode raw = sample.get("raw");
            Outer parsed = loose.parse(raw, Outer.class);
            JsonNode actual = mapper.valueToTree(parsed);
            assertEquals(sample.get("parsed"), actual, "宽松解析结果不一致，输入 " + raw);
        }
    }
}
