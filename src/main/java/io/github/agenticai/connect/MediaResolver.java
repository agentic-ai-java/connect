package io.github.agenticai.connect;

import java.util.Base64;
import java.util.Objects;

/** Host-provided media resolution; the library does not perform network requests. */
@FunctionalInterface
public interface MediaResolver {
    MediaData resolve(String source);

    record MediaData(String base64, String mimeType) {
        public MediaData {
            Objects.requireNonNull(base64); Objects.requireNonNull(mimeType);
            Base64.getDecoder().decode(base64);
        }
    }
    static MediaData decodeDataUrl(String url) {
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0 || !url.substring(0, comma).endsWith(";base64"))
            throw new ConversionException("媒体必须是 base64 data URL，或由宿主 MediaResolver 解析");
        String mime = url.substring(5, comma - 7);
        return new MediaData(url.substring(comma + 1), mime.isEmpty() ? "text/plain" : mime);
    }
}
