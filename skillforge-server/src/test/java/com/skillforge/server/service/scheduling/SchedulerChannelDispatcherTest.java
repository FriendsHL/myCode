package com.skillforge.server.service.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.server.channel.ChannelConfigService;
import com.skillforge.server.channel.delivery.ReplyDeliveryService;
import com.skillforge.server.channel.registry.ChannelAdapterRegistry;
import com.skillforge.server.channel.spi.ChannelAdapter;
import com.skillforge.server.channel.spi.ChannelConfigDecrypted;
import com.skillforge.server.channel.spi.ChannelReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulerChannelDispatcher")
class SchedulerChannelDispatcherTest {

    @Mock private ChannelAdapterRegistry adapterRegistry;
    @Mock private ChannelConfigService configService;
    @Mock private ReplyDeliveryService deliveryService;
    @Mock private ChannelAdapter feishuAdapter;

    private SchedulerChannelDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new SchedulerChannelDispatcher(
                adapterRegistry, configService, deliveryService, new ObjectMapper());
    }

    @Test
    @DisplayName("null channelTarget is a silent no-op")
    void nullTarget_noOp() {
        String err = dispatcher.pushResult(null, "sess", 1L, "hello");
        assertThat(err).isNull();
        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("blank text is a silent no-op")
    void blankText_noOp() {
        String err = dispatcher.pushResult(
                "{\"channelType\":\"feishu\",\"channelId\":\"oc\"}", "sess", 1L, "  ");
        assertThat(err).isNull();
    }

    @Test
    @DisplayName("delivers via ReplyDeliveryService when adapter + config exist")
    void delivers_when_resolved() {
        when(adapterRegistry.get("feishu")).thenReturn(Optional.of(feishuAdapter));
        when(configService.getDecryptedConfig("feishu")).thenReturn(
                Optional.of(new ChannelConfigDecrypted(1L, "feishu", "secret", "{}", "{}", null)));

        String err = dispatcher.pushResult(
                "{\"channelType\":\"feishu\",\"channelId\":\"oc_xxx\"}",
                "sess-1", 42L, "hello");

        assertThat(err).isNull();
        ArgumentCaptor<ChannelReply> replyCaptor = ArgumentCaptor.forClass(ChannelReply.class);
        verify(deliveryService).deliver(replyCaptor.capture(),
                eq(feishuAdapter), any(), eq("sess-1"));
        ChannelReply reply = replyCaptor.getValue();
        assertThat(reply.platform()).isEqualTo("feishu");
        assertThat(reply.conversationId()).isEqualTo("oc_xxx");
        assertThat(reply.markdownText()).isEqualTo("hello");
        // Synthesized inboundMessageId encodes the run id for traceability.
        assertThat(reply.inboundMessageId()).startsWith("scheduler:42:");
    }

    @Test
    @DisplayName("missing adapter returns error message, does not throw")
    void missingAdapter_returnsError() {
        when(adapterRegistry.get("alien")).thenReturn(Optional.empty());

        String err = dispatcher.pushResult(
                "{\"channelType\":\"alien\",\"channelId\":\"x\"}", "sess", 1L, "hi");

        assertThat(err).contains("no adapter").contains("alien");
        verify(deliveryService, never()).deliver(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("missing config returns error message")
    void missingConfig_returnsError() {
        when(adapterRegistry.get("feishu")).thenReturn(Optional.of(feishuAdapter));
        when(configService.getDecryptedConfig("feishu")).thenReturn(Optional.empty());

        String err = dispatcher.pushResult(
                "{\"channelType\":\"feishu\",\"channelId\":\"oc\"}", "sess", 1L, "hi");

        assertThat(err).contains("no active channel config").contains("feishu");
    }

    @Test
    @DisplayName("delivery exception is swallowed, returns error string")
    void deliveryThrows_swallowedAndReportsError() {
        when(adapterRegistry.get("feishu")).thenReturn(Optional.of(feishuAdapter));
        when(configService.getDecryptedConfig("feishu")).thenReturn(
                Optional.of(new ChannelConfigDecrypted(1L, "feishu", "secret", "{}", "{}", null)));
        org.mockito.Mockito.doThrow(new RuntimeException("upstream 503"))
                .when(deliveryService).deliver(any(), any(), any(), anyString());

        String err = dispatcher.pushResult(
                "{\"channelType\":\"feishu\",\"channelId\":\"oc\"}", "sess", 1L, "hi");

        assertThat(err).contains("channel push error").contains("upstream 503");
    }

    @Test
    @DisplayName("invalid JSON is reported but does NOT throw (INV-9)")
    void badJson_returnsError() {
        String err = dispatcher.pushResult("{ not json", "sess", 1L, "hi");
        assertThat(err).contains("channel push error");
    }

    @Test
    @DisplayName("missing channelType in JSON is rejected")
    void missingChannelType_returnsError() {
        String err = dispatcher.pushResult("{\"channelId\":\"oc\"}", "sess", 1L, "hi");
        assertThat(err).contains("channelType");
    }

    @Test
    @DisplayName("missing channelId in JSON is rejected")
    void missingChannelId_returnsError() {
        String err = dispatcher.pushResult("{\"channelType\":\"feishu\"}", "sess", 1L, "hi");
        assertThat(err).contains("channelId");
    }
}
