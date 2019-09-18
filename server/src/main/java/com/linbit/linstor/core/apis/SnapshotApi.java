package com.linbit.linstor.core.apis;

import com.linbit.linstor.core.objects.SnapshotDefinition;
import com.linbit.linstor.core.objects.SnapshotVolume;
import com.linbit.linstor.core.objects.SnapshotDefinition.SnapshotDfnApi;
import com.linbit.linstor.core.objects.SnapshotVolume.SnapshotVlmApi;

import java.util.List;
import java.util.UUID;

public interface SnapshotApi
{
    SnapshotDefinition.SnapshotDfnApi getSnaphotDfn();
    UUID getSnapshotUuid();
    long getFlags();
    boolean getSuspendResource();
    boolean getTakeSnapshot();
    Long getFullSyncId();
    Long getUpdateId();
    List<? extends SnapshotVolume.SnapshotVlmApi> getSnapshotVlmList();
}