package com.interviewer.core.config;

import com.interviewer.core.error.ConfigException;
import com.interviewer.core.error.CredentialMissingException;
import com.interviewer.core.provider.Providers;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * API Key 的唯一出入口。密钥进 Windows 凭据管理器，绝不落到配置文件里。
 *
 * <p>目标名是 {@code Interviewer.AI:<供应商>}，一个供应商一条凭据。Python 版的
 * keyring 在同名 service 下只能放一条，多出来的要退化成 {@code 用户名@service}
 * 复合名——这里不复刻那套，直接给每个供应商独立的目标名。
 *
 * <p>代价是：从 Python 版切过来的用户需要重新填一次 Key。
 *
 * <p>凭据库不可用时直接报错，不做任何本地明文兜底：那等于把卖点反过来砸掉。
 */
@Component
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    private static final String SERVICE = "Interviewer.AI";

    private final boolean windows =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    public void set(String providerKey, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            delete(providerKey);
            return;
        }
        requireWindows();
        byte[] blob = apiKey.strip().getBytes(StandardCharsets.UTF_16LE);
        Memory buffer = new Memory(blob.length);
        buffer.write(0, blob, 0, blob.length);

        WinCred.CREDENTIAL credential = new WinCred.CREDENTIAL();
        credential.type = WinCred.CRED_TYPE_GENERIC;
        credential.targetName = new WString(target(providerKey));
        credential.userName = new WString(providerKey);
        credential.credentialBlobSize = blob.length;
        credential.credentialBlob = buffer;
        credential.persist = WinCred.CRED_PERSIST_LOCAL_MACHINE;

        if (!WinCred.INSTANCE.CredWriteW(credential, 0)) {
            throw new ConfigException("CredWriteW 失败 err=" + Native.getLastError(),
                    "系统凭据库不可用，无法保存 API Key");
        }
        log.info("已保存 API Key provider={}", providerKey);
    }

    public String get(String providerKey) {
        if (!windows) {
            return "";
        }
        PointerByReference out = new PointerByReference();
        if (!WinCred.INSTANCE.CredReadW(
                new WString(target(providerKey)), WinCred.CRED_TYPE_GENERIC, 0, out)) {
            int err = Native.getLastError();
            if (err == WinCred.ERROR_NOT_FOUND) {
                return "";
            }
            throw new ConfigException("CredReadW 失败 err=" + err,
                    "系统凭据库不可用，无法读取 API Key");
        }
        Pointer raw = out.getValue();
        try {
            WinCred.CREDENTIAL credential = new WinCred.CREDENTIAL(raw);
            if (credential.credentialBlob == null || credential.credentialBlobSize <= 0) {
                return "";
            }
            byte[] blob = credential.credentialBlob.getByteArray(0, credential.credentialBlobSize);
            return new String(blob, StandardCharsets.UTF_16LE);
        } finally {
            WinCred.INSTANCE.CredFree(raw);
        }
    }

    /** 取不到就抛，带上供应商中文名——用户得知道去填哪一个。 */
    public String require(String providerKey) {
        String key = get(providerKey);
        if (key.isEmpty()) {
            throw new CredentialMissingException(
                    "「" + Providers.displayName(providerKey) + "」尚未配置 API Key，请前往设置填写");
        }
        return key;
    }

    public void delete(String providerKey) {
        if (!windows) {
            return;
        }
        // 删不存在的凭据不是错误：清空输入框保存就是这个路径
        WinCred.INSTANCE.CredDeleteW(
                new WString(target(providerKey)), WinCred.CRED_TYPE_GENERIC, 0);
    }

    public boolean present(String providerKey) {
        return !get(providerKey).isEmpty();
    }

    /** 已配置 Key 的供应商集合。启动前检查用它。 */
    public Set<String> configured() {
        Set<String> found = new LinkedHashSet<>();
        for (String key : Providers.credentialKeys()) {
            if (present(key)) {
                found.add(key);
            }
        }
        return found;
    }

    private void requireWindows() {
        if (!windows) {
            throw new ConfigException("当前平台没有 Windows 凭据管理器",
                    "系统凭据库不可用，无法保存 API Key");
        }
    }

    private static String target(String providerKey) {
        return SERVICE + ":" + providerKey;
    }
}
