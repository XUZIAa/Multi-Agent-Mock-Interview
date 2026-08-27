package com.interviewer.data.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewer.core.Text;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 真题语料的只读检索。
 *
 * <p>语料是离线编译的产物。检索命中为空时调用方应当什么都不注入，让出题回到原有行为——
 * 这套东西的立身之本是贴合简历的项目题，通用八股只是补充，不能把项目题挤掉。
 */
@Component
public class CorpusStore {

    private static final Logger log = LoggerFactory.getLogger(CorpusStore.class);

    private static final String RESOURCE = "/corpus/questions.bin";

    /** 英文技术词：Redis、MySQL、Spring Boot、C++、.NET 之类。 */
    private static final Pattern LATIN = Pattern.compile("[A-Za-z][A-Za-z0-9+#.\\-]{1,20}");

    /** 这些词到处都在，用来检索等于全表扫描。 */
    private static final Set<String> STOP = Set.of(
            "the", "and", "for", "with", "http", "https", "com", "www", "api", "sdk",
            "开发", "使用", "熟悉", "掌握", "了解", "熟练", "经验", "能力", "以上", "相关",
            "负责", "参与", "以及", "或者", "包括", "优先", "良好", "具备", "工作", "技术");

    /**
     * 语料只覆盖 Java 后端与 Agent／大模型应用两个方向。
     *
     * <p>别的岗位即使 JD 里同样出现 MySQL、Redis、Linux，考察视角也完全不同
     * （运维关心集群与容量，这里的题是 InnoDB 索引结构），一律不注入。
     */
    private static final List<String> OFF_SCOPE_TITLE = List.of(
            "测试", "运维", "SRE", "sre", "DBA", "dba", "产品", "运营", "设计", "UI", "ui",
            "数据分析", "数据仓库", "数仓", "BI", "ETL", "商业智能",
            "算法", "机器学习", "深度学习", "视觉", "语音", "推荐系统", "搜索引擎研发",
            "嵌入式", "单片机", "硬件", "FPGA", "驱动", "固件", "电路", "射频",
            "安卓", "Android", "android", "iOS", "ios", "客户端", "鸿蒙", "小程序",
            "游戏", "Unity", "unity", "虚幻", "美术", "策划",
            "安全", "渗透", "逆向", "风控", "合规", "审计",
            "实施", "售前", "售后", "支持", "培训", "销售", "财务", "人力", "行政", "法务",
            "C++", "Golang 开发", "Go 开发", "PHP", "Python 开发", ".NET", "Net 开发");

    private static final List<String> JAVA_SIGNAL = List.of(
            "Java", "java", "JAVA", "Spring", "spring", "SpringBoot", "JVM", "jvm",
            "MyBatis", "mybatis", "Mybatis", "Tomcat", "tomcat", "JUC", "juc",
            "Netty", "netty", "Dubbo", "dubbo", "Maven", "maven", "Gradle", "JDK", "jdk");

    private static final List<String> AGENT_SIGNAL = List.of(
            "Agent", "agent", "智能体", "RAG", "rag", "LLM", "llm", "大模型", "大语言模型",
            "Prompt", "prompt", "提示词", "向量数据库", "Embedding", "embedding",
            "LangChain", "langchain", "AIGC", "aigc", "多模态", "微调", "MCP", "mcp");

    /** 只命中一次可能是巧合，要求两个以上信号才放行。 */
    private static final int MIN_SIGNAL = 2;

    private final ObjectMapper mapper;
    private final Map<String, Pattern> boundaryCache = new ConcurrentHashMap<>();
    private volatile List<RealQuestion> items;

    public CorpusStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 懒加载。没有语料就是空列表，一切照旧。 */
    private List<RealQuestion> load() {
        List<RealQuestion> cached = items;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (items != null) {
                return items;
            }
            items = read();
            return items;
        }
    }

    private List<RealQuestion> read() {
        byte[] blob;
        try (InputStream in = CorpusStore.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.info("没有真题语料，出题按原有流程走");
                return List.of();
            }
            blob = in.readAllBytes();
        } catch (Exception e) {
            log.warn("真题语料读取失败，已忽略: {}", e.getMessage());
            return List.of();
        }
        try {
            JsonNode payload = mapper.readTree(inflate(blob));
            List<RealQuestion> out = new ArrayList<>();
            for (JsonNode item : payload.path("items")) {
                String text = item.path("q").asText("");
                if (Text.notBlank(text)) {
                    out.add(new RealQuestion(item.path("c").asText(""), text,
                            Math.max(1, item.path("n").asInt(1))));
                }
            }
            log.info("真题语料已载入 {} 条", out.size());
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("真题语料解析失败，已忽略: {}", e.getMessage());
            return List.of();
        }
    }

    /** zlib 解压。语料是 zlib.compress 出来的，对应 Java 的 Inflater 默认（带 zlib 头）。 */
    private static String inflate(byte[] blob) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(blob);
        ByteArrayOutputStream out = new ByteArrayOutputStream(blob.length * 4);
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(buffer, 0, n);
            }
        } finally {
            inflater.end();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * 判断岗位是否落在语料覆盖范围内。
     *
     * <p>排斥词只看岗位名：JD 正文里出现「需要写单元测试」不代表这是测试岗。
     * 方向信号看岗位名加 JD，要求命中两个以上，避免偶然提一次 Java 就放行。
     */
    public static boolean jobInScope(String title, List<String> fragments) {
        String name = Text.safe(title);
        for (String bad : OFF_SCOPE_TITLE) {
            if (name.contains(bad)) {
                return false;
            }
        }
        StringBuilder blob = new StringBuilder(name);
        fragments.forEach(f -> blob.append(' ').append(Text.safe(f)));
        String all = blob.toString();
        long java = JAVA_SIGNAL.stream().filter(all::contains).count();
        long agent = AGENT_SIGNAL.stream().filter(all::contains).count();
        return java >= MIN_SIGNAL || agent >= MIN_SIGNAL;
    }

    /**
     * 按技能词检索真题。
     *
     * <p>先过岗位方向门禁；命中不足 minUseful 时判定覆盖不够，返回空让调用方保持原行为；
     * perCategory 限制单一技术栈的占比，避免参考题偏科。
     */
    public List<RealQuestion> search(List<String> fragments, String title) {
        return search(fragments, title, 28, 4, 2, 8);
    }

    public List<RealQuestion> search(List<String> fragments, String title, int limit,
                                     int perCategory, int minSources, int minUseful) {
        List<RealQuestion> all = load();
        if (all.isEmpty() || !jobInScope(title, fragments)) {
            return List.of();
        }
        List<String> terms = terms(fragments);
        if (terms.isEmpty()) {
            return List.of();
        }

        List<Probe> probes = new ArrayList<>(terms.size());
        terms.forEach(t -> probes.add(new Probe(t.toLowerCase(Locale.ROOT), boundary(t))));

        List<Scored> scored = new ArrayList<>();
        for (RealQuestion q : all) {
            if (q.sources() < minSources) {
                continue;
            }
            String text = q.text().toLowerCase(Locale.ROOT);
            String category = q.category().toLowerCase(Locale.ROOT);
            int hits = 0;
            for (Probe probe : probes) {
                // 先用子串粗筛，绝大多数题在这一步就被排除
                if (!text.contains(probe.lower()) && !category.contains(probe.lower())) {
                    continue;
                }
                if (probe.pattern() != null
                        && !probe.pattern().matcher(q.text()).find()
                        && !probe.pattern().matcher(q.category()).find()) {
                    continue;
                }
                hits++;
            }
            if (hits > 0) {
                scored.add(new Scored(hits, q.sources(), q));
            }
        }

        if (scored.size() < minUseful) {
            return List.of();
        }
        scored.sort(Comparator.comparingInt(Scored::hits).reversed()
                .thenComparing(Comparator.comparingInt(Scored::sources).reversed()));

        List<RealQuestion> picked = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        for (Scored row : scored) {
            String category = row.question().category();
            int seen = used.getOrDefault(category, 0);
            if (seen >= perCategory) {
                continue;
            }
            used.put(category, seen + 1);
            picked.add(row.question());
            if (picked.size() >= limit) {
                break;
            }
        }
        return List.copyOf(picked);
    }

    private record Probe(String lower, Pattern pattern) {
    }

    private record Scored(int hits, int sources, RealQuestion question) {
    }

    /**
     * 从 JD 条目与技能列表里抠出可用于匹配的词。
     *
     * <p>JD 条目是整句，只取其中的英文技术词；技能名本身够短就整体当词用。
     * 长词优先，先匹配 Spring Boot 再匹配 Spring。
     */
    private static List<String> terms(List<String> fragments) {
        Set<String> found = new LinkedHashSet<>();
        for (String fragment : fragments) {
            String raw = Text.safe(fragment).strip();
            if (raw.isEmpty()) {
                continue;
            }
            Matcher m = LATIN.matcher(raw);
            while (m.find()) {
                String word = strip(m.group());
                if (word.length() >= 2 && !STOP.contains(word.toLowerCase(Locale.ROOT))) {
                    found.add(word);
                }
            }
            if (raw.length() >= 2 && raw.length() <= 12 && !STOP.contains(raw)) {
                found.add(raw);
            }
        }
        List<String> out = new ArrayList<>(found);
        out.sort(Comparator.comparingInt(String::length).reversed());
        return out;
    }

    private static String strip(String word) {
        int start = 0;
        int end = word.length();
        while (start < end && (word.charAt(start) == '.' || word.charAt(start) == '-')) {
            start++;
        }
        while (end > start && (word.charAt(end - 1) == '.' || word.charAt(end - 1) == '-')) {
            end--;
        }
        return word.substring(start, end);
    }

    /**
     * 英文词要卡词边界，否则 CAN 会命中 canal、ID 会命中 android。
     * 中文没有词边界，返回 null 走子串匹配。
     */
    private Pattern boundary(String term) {
        if (!isAscii(term)) {
            return null;
        }
        return boundaryCache.computeIfAbsent(term, t -> Pattern.compile(
                "(?<![A-Za-z0-9])" + Pattern.quote(t) + "(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE));
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
