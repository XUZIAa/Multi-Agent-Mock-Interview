package com.interviewer.rpc.dto;

import java.util.List;

/** 供应商目录。前端据此渲染下拉，不必把这些常量抄一遍。 */
public record Catalog(List<ProviderOption> chat, List<ProviderOption> realtime,
                      List<RoleOption> roles) {
}
