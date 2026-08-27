package com.interviewer.domain.company;

import com.interviewer.core.type.CompanyTier;
import com.interviewer.core.type.JobLevel;
import com.interviewer.core.type.ScoreDimension;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 八类公司的考察画像。文案是调过的，改动会直接改变出题与评分口径。 */
public final class Companies {

    private static final Map<CompanyTier, CompanyProfile> PROFILES = build();

    /** 基础评分权重。公司偏移在此之上相乘，得到那类公司真实的口径。 */
    private static final Map<ScoreDimension, Double> BASE_WEIGHTS = Map.of(
            ScoreDimension.TECH_DEPTH, 0.32,
            ScoreDimension.EXPRESSION, 0.20,
            ScoreDimension.RESILIENCE, 0.14,
            ScoreDimension.VALUE_FIT, 0.12,
            ScoreDimension.CODING, 0.14,
            ScoreDimension.COLLABORATION, 0.08);

    private static final Map<JobLevel, String> LEVEL_EXPECTATION = Map.of(
            JobLevel.INTERN, "考察基础是否扎实、学习能力、有没有动手做过完整的东西，不要求架构视野",
            JobLevel.JUNIOR, "考察独立完成模块的能力、基础原理、能否按规范交付，允许在架构问题上答不全",
            JobLevel.MID, "考察独立负责子系统、技术选型理由、跨模块协作，要能讲清权衡",
            JobLevel.SENIOR, "考察架构设计、复杂问题拆解、带人与推动落地，必须能讲清取舍和风险",
            JobLevel.EXPERT, "考察技术判断力、跨团队影响、长期演进规划，要能对方案下结论并承担后果");

    private Companies() {
    }

    public static CompanyProfile of(CompanyTier tier) {
        return PROFILES.get(tier == null ? CompanyTier.MID_TECH : tier);
    }

    public static List<CompanyProfile> all() {
        return java.util.Arrays.stream(CompanyTier.values()).map(PROFILES::get).toList();
    }

    /** 在基础权重上叠加公司偏移，得到这类公司真实的评分口径。 */
    public static Map<ScoreDimension, Double> scoreWeights(CompanyTier tier) {
        Map<ScoreDimension, Double> bias = of(tier).scoreBias();
        Map<ScoreDimension, Double> result = new EnumMap<>(ScoreDimension.class);
        BASE_WEIGHTS.forEach((dim, weight) ->
                result.put(dim, weight * bias.getOrDefault(dim, 1.0)));
        return result;
    }

    /** 同一岗位不同级别的考察落点差异。 */
    public static String levelExpectation(CompanyTier tier, JobLevel level) {
        CompanyProfile profile = of(tier);
        String base = LEVEL_EXPECTATION.get(level == null ? JobLevel.MID : level);
        return base + "。结合" + profile.tier().label() + "的口径：" + profile.styleHeadline() + "。";
    }

    private static Map<CompanyTier, CompanyProfile> build() {
        Map<CompanyTier, CompanyProfile> map = new LinkedHashMap<>();

        map.put(CompanyTier.BIG_TECH, new CompanyProfile(
                CompanyTier.BIG_TECH,
                "人多、系统大、分工细，招人看基础深度和规模经验",
                "先问项目再往原理层追，喜欢连续追问直到你答不出来；"
                        + "关心数据规模、QPS、延迟指标，几乎必问「量级放大十倍会怎样」；"
                        + "算法题是标配，答不出会明显影响评价",
                List.of("高并发", "分布式一致性", "缓存与存储选型", "算法复杂度", "线上故障排查", "监控与指标"),
                List.of("具体某个 IDE 的用法", "行业特定业务流程"),
                "强调技术栈深度、大规模系统经验、扎实的计算机基础，通常明确写出算法与数据结构要求",
                9, 9, 9, 7, 4, 3, 6,
                Map.of(ScoreDimension.TECH_DEPTH, 1.15, ScoreDimension.CODING, 1.10)));

        map.put(CompanyTier.MID_TECH, new CompanyProfile(
                CompanyTier.MID_TECH,
                "团队不大、一人多岗，招人看能不能独立把事情做完",
                "重点问「这个模块是不是你一个人做的」「上线后出问题谁处理」；"
                        + "喜欢全栈或跨模块能力，会问你不熟的相邻领域看你怎么应对；"
                        + "不太抠算法，但很在意交付速度和独立性",
                List.of("独立负责的模块", "技术选型理由", "上线与回滚", "跨模块协作", "问题定位过程"),
                List.of("超大规模架构", "论文级算法优化"),
                "强调独立负责能力、全栈或多技术栈、快速上手、能扛业务需求",
                5, 6, 6, 9, 5, 5, 7,
                Map.of(ScoreDimension.TECH_DEPTH, 1.00, ScoreDimension.COLLABORATION, 1.10)));

        map.put(CompanyTier.STARTUP, new CompanyProfile(
                CompanyTier.STARTUP,
                "资源紧、变化快，招人看主动性和扛事能力",
                "几乎不问八股，直接问你做过什么、带来什么结果；"
                        + "会用「资源只有一半你怎么办」「这事没人管你会不会接」这类问题测 ownership；"
                        + "对成本和时间极度敏感，会问「为什么不用现成的」",
                List.of("从 0 到 1 的经历", "ownership", "资源受限下的取舍", "成本控制", "主动补位"),
                List.of("大公司流程", "多层审批机制"),
                "不设明确边界，强调主动性、抗压、从 0 搭建能力，常写「能接受快速变化」",
                3, 5, 5, 10, 2, 7, 5,
                Map.of(ScoreDimension.VALUE_FIT, 1.20, ScoreDimension.RESILIENCE, 1.15)));

        map.put(CompanyTier.MANUFACTURING, new CompanyProfile(
                CompanyTier.MANUFACTURING,
                "软件服务产线与设备，停机就是钱，招人看稳定和规范",
                "反复确认系统稳定性：「产线不能停，你的程序挂了怎么办」；"
                        + "关心与硬件、PLC、设备、产线人员的配合经验；"
                        + "在意文档、变更流程、现场调试和故障复现能力，几乎不问算法",
                List.of("系统稳定性与容错", "现场问题排查", "与硬件/设备联调", "变更与发布流程",
                        "数据采集与实时性", "文档与交接"),
                List.of("互联网高并发", "算法竞赛题", "前端炫技"),
                "强调工业场景经验（MES/SCADA/PLC/嵌入式/数采）、稳定性要求、规范意识、能下现场",
                2, 4, 5, 8, 9, 8, 10,
                Map.of(ScoreDimension.RESILIENCE, 1.10, ScoreDimension.COLLABORATION, 1.20)));

        map.put(CompanyTier.STATE_OWNED, new CompanyProfile(
                CompanyTier.STATE_OWNED,
                "信息化建设为主，流程规范优先，招人看沉稳与合规",
                "语气克制、按提纲走，很少高压追问；"
                        + "关心项目验收、需求对接、多方协调、文档交付；"
                        + "会问信息安全、等保、国产化替代相关的经验",
                List.of("需求对接与验收", "文档与规范", "信息安全合规", "国产化适配", "多部门协作", "系统运维"),
                List.of("激进的技术选型", "996 式交付"),
                "强调信息化项目经验、规范文档、安全合规、国产化环境适配，通常有学历与稳定性要求",
                3, 5, 5, 6, 10, 6, 9,
                Map.of(ScoreDimension.COLLABORATION, 1.20, ScoreDimension.VALUE_FIT, 1.10)));

        map.put(CompanyTier.FOREIGN, new CompanyProfile(
                CompanyTier.FOREIGN,
                "流程成熟、跨时区协作，招人看工程素养和沟通",
                "中英混说，名词一律英文；关心 ownership、impact、stakeholder 沟通；"
                        + "重视代码质量、测试覆盖、code review 文化；"
                        + "会问「你怎么说服对方」「有分歧时怎么 align」",
                List.of("代码质量与测试", "code review", "跨时区协作", "影响力与说服", "工程规范", "技术文档"),
                List.of("拼时长", "野路子救火"),
                "强调英文沟通、工程规范、单元测试、敏捷流程，常写 global team 协作要求",
                6, 7, 7, 7, 8, 5, 8,
                Map.of(ScoreDimension.EXPRESSION, 1.15, ScoreDimension.COLLABORATION, 1.15)));

        map.put(CompanyTier.FINANCE, new CompanyProfile(
                CompanyTier.FINANCE,
                "钱不能错、系统不能停，招人看严谨与容灾",
                "对数据一致性刨根问底：「这笔钱扣了但没到账怎么办」；"
                        + "必问事务、对账、幂等、重试、容灾切换；"
                        + "关心合规审计与操作留痕，对「大概没问题」零容忍",
                List.of("分布式事务", "幂等与对账", "高可用与容灾", "审计留痕", "资金安全", "灰度与回滚"),
                List.of("前端动效", "快速试错"),
                "强调金融级稳定性、分布式事务、灾备演练、合规审计，通常要求相关行业背景",
                6, 8, 8, 7, 9, 4, 10,
                Map.of(ScoreDimension.TECH_DEPTH, 1.10, ScoreDimension.RESILIENCE, 1.10)));

        map.put(CompanyTier.OUTSOURCE, new CompanyProfile(
                CompanyTier.OUTSOURCE,
                "按甲方要求交付，招人看上手速度和适配能力",
                "先核对技术栈清单，逐项确认你会不会、做过几年；"
                        + "关心能否驻场、能否同时跟多个项目、能否适应甲方规范；"
                        + "问题偏广不偏深，更在意「拿来就能干」",
                List.of("技术栈清单核对", "交付周期", "多项目并行", "甲方规范适配", "驻场经验"),
                List.of("底层原理探究", "长期架构演进"),
                "逐条列出技术栈与版本、明确项目周期与驻场要求，强调即刻上手",
                3, 4, 4, 8, 7, 8, 6,
                Map.of(ScoreDimension.EXPRESSION, 1.05, ScoreDimension.COLLABORATION, 1.10)));

        return Map.copyOf(map);
    }
}
