package io.github.agenticai.connect;

import com.google.gson.JsonObject;

/** Optional provider dialect applied once, after all request protocol conversion steps. */
@FunctionalInterface
public interface ProviderProfile {
    /** Mutates the copied final payload; diagnostics participate in the request loss policy. */
    void adapt(JsonObject request, Adaptation adaptation);

    /** Override when this profile, rather than the generic codec, renders reasoning controls. */
    default boolean rendersReasoning() { return false; }

    /** Current DeepSeek Chat dialect; select explicitly, never infer from a model name. */
    static ProviderProfile deepSeekChat() { return DeepSeekChatProfile.INSTANCE; }

    /** Request-scoped access to the final format, portable intent, and loss reporting. */
    final class Adaptation {
        private final Work work;
        Adaptation(Work work) { this.work = work; }
        public Format from() { return work.from; }
        public Format to() { return work.to; }
        public ConversionContext context() { return work.ctx; }
        public Reasoning.Intent reasoning() { return work.ctx.reasoning; }
        public void warning(String code, String path, String message) { work.warn(code, path, message); }
        public void error(String code, String path, String message) { work.loss(code, path, message); }
    }
}
