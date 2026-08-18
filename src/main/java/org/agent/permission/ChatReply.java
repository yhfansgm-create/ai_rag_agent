package org.agent.permission;

/** 对话响应（避免与 Spring AI 的 ChatResponse 重名） */
public record ChatReply(String answer) {
}
