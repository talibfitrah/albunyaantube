package com.albunyaan.tube.dto.registry;

/** Error envelope with code + i18n message key. */
public record PreviewError(PreviewErrorCode code, String messageKey) {
    public static PreviewError of(PreviewErrorCode code) {
        return new PreviewError(code, "contentSearch.bulk.errors." + code.name());
    }
}
