package com.interviewer.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.interviewer.core.error.ConfigException;
import com.interviewer.domain.coding.CodingCase;
import com.interviewer.domain.coding.JudgeOutcome;
import com.interviewer.domain.coding.RunOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 代码沙盒。
 *
 * <p>子进程的坑全在这里：管道填满会让子进程卡死在写上、超时后不强杀会留下孤儿、中文输出
 * 按系统代码页读就是乱码、按行比对不忽略行尾空白就会因为一个换行判错。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CodeRunnerTest {

    @Autowired
    private CodeRunner runner;

    private boolean hasPython() {
        try {
            runner.run("python", "pass", "");
            return true;
        } catch (ConfigException e) {
            return false;
        }
    }

    @Test
    void runsCodeAndCapturesOutput() {
        assumeTrue(hasPython(), "这台机器 PATH 里没有 Python，跳过");

        RunOutcome ok = runner.run("python", "print('你好', 1 + 1)", "");
        assertTrue(ok.ok(), "正常退出应当是成功");
        assertEquals("你好 2", ok.stdout().strip(), "中文输出必须按 UTF-8 读回来");
        assertEquals(0, ok.exitCode());
        assertFalse(ok.timedOut());
    }

    @Test
    void readsStdinAndReportsFailure() {
        assumeTrue(hasPython(), "这台机器 PATH 里没有 Python，跳过");

        RunOutcome echoed = runner.run("python",
                "import sys\nprint(sys.stdin.read().strip().upper())", "abc\n");
        assertEquals("ABC", echoed.stdout().strip());

        RunOutcome boom = runner.run("python", "raise ValueError('炸了')", "");
        assertFalse(boom.ok(), "抛异常的程序不该算成功");
        assertTrue(boom.stderr().contains("ValueError"), "错误流要带回去给面试官追问");
    }

    @Test
    void killsRunawayCode() {
        assumeTrue(hasPython(), "这台机器 PATH 里没有 Python，跳过");

        // 死循环是候选人最常写出来的东西，必须强杀而不是挂住整个后端
        RunOutcome stuck = runner.run("python", "while True:\n    pass", "", 1500);
        assertTrue(stuck.timedOut(), "死循环必须被判超时");
        assertFalse(stuck.ok());
        assertTrue(stuck.durationMs() < 15_000, "强杀要及时，不能等到别的超时兜底");
    }

    @Test
    void floodedOutputDoesNotDeadlock() {
        assumeTrue(hasPython(), "这台机器 PATH 里没有 Python，跳过");

        // 输出量远超管道缓冲：不并发读走两个流，子进程会阻塞在写上直到超时
        RunOutcome flood = runner.run("python",
                "for i in range(20000):\n    print('x' * 40)", "");
        assertFalse(flood.timedOut(), "刷屏不该被误判成超时——那说明管道没读干");
        assertTrue(flood.stdout().contains("已截断"), "超长输出要截断，不能整份塞回界面");
    }

    @Test
    void judgesCasesIgnoringTrailingWhitespace() {
        assumeTrue(hasPython(), "这台机器 PATH 里没有 Python，跳过");

        JudgeOutcome outcome = runner.judge("python",
                "n = int(input())\nprint(n * 2)",
                List.of(new CodingCase("3", "6   ", ""), new CodingCase("5", "10", "")));
        assertEquals(2, outcome.total());
        assertEquals(2, outcome.passed(), "行尾空白不该导致判错");

        JudgeOutcome wrong = runner.judge("python",
                "n = int(input())\nprint(n + 1)",
                List.of(new CodingCase("3", "6", "")));
        assertEquals(0, wrong.passed());
    }
}
