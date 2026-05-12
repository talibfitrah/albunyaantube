package com.albunyaan.tube.dto.sync;

/**
 * Plan D — base shape for every sync row in pull responses and push echoes.
 * `updatedAt` is the server timestamp in epoch milliseconds; `deleted=true`
 * means tombstone (real or virtual). Concrete subclasses add per-type payload.
 */
public abstract class SyncRowDto {
    private String entityId;
    private boolean deleted;
    private long updatedAt;

    public String getEntityId()         { return entityId; }
    public void setEntityId(String v)   { this.entityId = v; }
    public boolean isDeleted()          { return deleted; }
    public void setDeleted(boolean v)   { this.deleted = v; }
    public long getUpdatedAt()          { return updatedAt; }
    public void setUpdatedAt(long v)    { this.updatedAt = v; }
}
