package com.albunyaan.tube.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PutSubscriptionRequest {
    @NotBlank
    private String channelUrl;
    @NotBlank
    private String name;
    private String avatarUrl;
    @NotNull
    private Long subscribedAt;

    public String getChannelUrl() {
        return channelUrl;
    }

    public void setChannelUrl(String v) {
        this.channelUrl = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String v) {
        this.avatarUrl = v;
    }

    public Long getSubscribedAt() {
        return subscribedAt;
    }

    public void setSubscribedAt(Long v) {
        this.subscribedAt = v;
    }
}
