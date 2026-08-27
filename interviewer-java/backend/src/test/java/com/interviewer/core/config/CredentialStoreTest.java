package com.interviewer.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.interviewer.core.error.CredentialMissingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 凭据库读写。
 *
 * <p>这一层是 JNA 直接调 Win32，结构体字段错位、字符编码不对、指针没释放，编译期一个都查
 * 不出来，只有真写一次才知道。用户上手第一步就是填 Key，这条链路断了整个应用都起不来。
 *
 * <p>用一个不存在的供应商键，跑完即删，不碰用户真实的那几条。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CredentialStoreTest {

    private static final String PROBE_KEY = "__interviewer_test_probe__";

    @Autowired
    private CredentialStore credentials;

    @AfterEach
    void cleanup() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            credentials.delete(PROBE_KEY);
        }
    }

    @Test
    void keyRoundTripsThroughTheSystemVault() {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "凭据库只在 Windows 上有，跳过");

        assertFalse(credentials.present(PROBE_KEY), "测试前不该已有这条凭据");
        assertThrows(CredentialMissingException.class, () -> credentials.require(PROBE_KEY),
                "取不到 Key 必须抛出可指路的异常");

        // 真实 Key 里有连字符和下划线，长度也不短，按真东西的样子写一份
        String secret = "sk-Test_密钥-2026ABCDEFghijkl0123456789";
        credentials.set(PROBE_KEY, secret);
        assertTrue(credentials.present(PROBE_KEY));
        assertEquals(secret, credentials.get(PROBE_KEY), "读回来必须逐字一致（UTF-16LE 往返）");
        assertEquals(secret, credentials.require(PROBE_KEY));

        // 覆盖写：用户换 Key 走的就是这条路径
        credentials.set(PROBE_KEY, "sk-Second");
        assertEquals("sk-Second", credentials.get(PROBE_KEY));

        // 清空输入框保存 = 删除，且不能因为「删不存在的」而报错
        credentials.set(PROBE_KEY, "");
        assertFalse(credentials.present(PROBE_KEY));
        credentials.delete(PROBE_KEY);
    }
}
