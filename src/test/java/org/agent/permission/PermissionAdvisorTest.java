package org.agent.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PermissionAdvisor 的单元测试：合法会话放行并写 context；非法会话 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class PermissionAdvisorTest {

    @Mock
    private SessionAuthService sessionAuthService;
    @Mock
    private CallAdvisorChain chain;

    @Test
    void validSession_putsEnvelopeAndFilterIntoContext() {
        SessionEnvelope envelope = new SessionEnvelope("s1", "10086", "TELLER", "T3", "0101", "0100/0101",
                2, List.of("kb-teller"), 0L, "MOCK", null,
                "kb_id in ['kb-teller'] && doc_level <= 2 && org_codes in ['0100/0101']");
        when(sessionAuthService.verifyAndLoad("s1", "tok")).thenReturn(envelope);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        PermissionAdvisor advisor = new PermissionAdvisor(sessionAuthService);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(PermissionAdvisor.PARAM_SESSION_ID, "s1");
        ctx.put(PermissionAdvisor.PARAM_SESSION_TOKEN, "tok");
        ChatClientRequest request = new ChatClientRequest(new Prompt("hi"), ctx);

        ChatClientResponse resp = advisor.adviseCall(request, chain);

        assertThat(resp).isNotNull();
        assertThat(ctx).containsKey(PermissionAdvisor.CONTEXT_ENVELOPE)
                .containsKey(PermissionAdvisor.CONTEXT_ACCESS_FILTER);
        assertThat(ctx.get(PermissionAdvisor.CONTEXT_ACCESS_FILTER)).isEqualTo(envelope.accessFilter());
        verify(chain, times(1)).nextCall(any());
    }

    @Test
    void missingSessionId_throwsAndNeverCallsChain() {
        PermissionAdvisor advisor = new PermissionAdvisor(sessionAuthService);
        ChatClientRequest request = new ChatClientRequest(new Prompt("hi"), new HashMap<>());

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.UNAUTHORIZED);
        verify(chain, never()).nextCall(any());
    }

    @Test
    void invalidSession_failsClosed() {
        when(sessionAuthService.verifyAndLoad("s1", "bad"))
                .thenThrow(new SessionAuthException(SessionAuthException.Code.TOKEN_MISMATCH, "令牌不匹配"));

        PermissionAdvisor advisor = new PermissionAdvisor(sessionAuthService);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(PermissionAdvisor.PARAM_SESSION_ID, "s1");
        ctx.put(PermissionAdvisor.PARAM_SESSION_TOKEN, "bad");
        ChatClientRequest request = new ChatClientRequest(new Prompt("hi"), ctx);

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(SessionAuthException.class)
                .extracting(e -> ((SessionAuthException) e).getCode())
                .isEqualTo(SessionAuthException.Code.TOKEN_MISMATCH);
        verify(chain, never()).nextCall(any());
    }
}
