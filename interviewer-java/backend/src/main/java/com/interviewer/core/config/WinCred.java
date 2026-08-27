package com.interviewer.core.config;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Windows 凭据管理器的 JNA 映射。
 *
 * <p>jna-platform 没有封装 Cred* 系列（5.19.1 里只有 Sspi 的 CredHandle），
 * 所以这里自己映射 advapi32 的四个函数。Python 版靠 keyring 库拿到同一份能力。
 *
 * <p>刻意不套 W32APIOptions：那套 FunctionMapper 会按 Unicode 规则改写函数名，
 * 而这里的导出名已经带了 W 后缀，直接按原名绑定最不容易出错。WString 本身就是
 * 宽字符指针，不依赖 TypeMapper。
 */
interface WinCred extends StdCallLibrary {

    WinCred INSTANCE = Native.load("advapi32", WinCred.class);

    /** 存的是 API Key，不是域凭据。 */
    int CRED_TYPE_GENERIC = 1;

    /** 只在本机持久化，不随漫游配置同步到别的机器。 */
    int CRED_PERSIST_LOCAL_MACHINE = 2;

    int ERROR_NOT_FOUND = 1168;

    boolean CredWriteW(CREDENTIAL credential, int flags);

    boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);

    boolean CredDeleteW(WString targetName, int type, int flags);

    void CredFree(Pointer buffer);

    /**
     * CREDENTIALW。字段顺序即内存布局，一个都不能挪。
     *
     * <p>LastWritten 是 FILETIME（两个 DWORD）。写入不用填、读取也不关心，
     * 但必须占住这 8 个字节，否则后面所有字段全部错位。
     */
    @Structure.FieldOrder({"flags", "type", "targetName", "comment",
            "lastWrittenLow", "lastWrittenHigh",
            "credentialBlobSize", "credentialBlob", "persist",
            "attributeCount", "attributes", "targetAlias", "userName"})
    class CREDENTIAL extends Structure {
        public int flags;
        public int type;
        public WString targetName;
        public WString comment;
        public int lastWrittenLow;
        public int lastWrittenHigh;
        public int credentialBlobSize;
        public Pointer credentialBlob;
        public int persist;
        public int attributeCount;
        public Pointer attributes;
        public WString targetAlias;
        public WString userName;

        public CREDENTIAL() {
            super();
        }

        public CREDENTIAL(Pointer memory) {
            super(memory);
            read();
        }
    }
}
