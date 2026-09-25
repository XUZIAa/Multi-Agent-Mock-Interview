package com.interviewer.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewer.Parity;
import com.interviewer.data.corpus.CorpusStore;
import com.interviewer.data.corpus.RealQuestion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 真题检索与 Python 原版逐条比对。
 *
 * <p>这条链路决定题库里补哪些通用题，而且是纯确定性的：zlib 解压、词边界匹配、岗位方向
 * 门禁、分类配额。门禁尤其要准——放宽了会给测试岗塞 InnoDB 索引题，收紧了真题就白编译。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CorpusParityTest {

    @Autowired
    private CorpusStore corpus;

    @Test
    void searchMatchesPythonBaseline() throws Exception {
        JsonNode root = Parity.load("corpus");
        Assumptions.assumeTrue(root != null, "没有 corpus 基准文件");

        for (JsonNode c : root.get("cases")) {
            String title = c.get("title").asText();
            List<String> fragments = new ArrayList<>();
            c.get("fragments").forEach(f -> fragments.add(f.asText()));

            assertEquals(c.get("in_scope").asBoolean(),
                    CorpusStore.jobInScope(title, fragments), title + " 岗位方向门禁");

            List<RealQuestion> hits = corpus.search(fragments, title);
            assertEquals(c.get("count").asInt(), hits.size(), title + " 命中条数");

            JsonNode want = c.get("hits");
            for (int i = 0; i < want.size(); i++) {
                RealQuestion got = hits.get(i);
                String tag = title + " 第 " + i + " 条";
                assertEquals(want.get(i).get("q").asText(), got.text(), tag + " 题面");
                assertEquals(want.get(i).get("c").asText(), got.category(), tag + " 分类");
                assertEquals(want.get(i).get("n").asInt(), got.sources(), tag + " 频次");
            }
        }
    }
}
