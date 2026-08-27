package com.interviewer.rpc.dto;

/** auto=true 表示由沉默监视器触发，用户并没点提词，失败时不打扰他。 */
public record HintBody(boolean auto) {
}
