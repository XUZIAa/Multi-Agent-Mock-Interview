package com.interviewer.rpc.dto;

/** 只表示「做完了」。前端据此判断请求是否被接受。 */
public record Ok(boolean ok) {

    public static final Ok DONE = new Ok(true);
}
