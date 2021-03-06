package com.linbit.linstor.event;

import com.linbit.ImplementationError;
import com.linbit.linstor.api.ApiConsts;

public interface LinstorTriggerableEvent<T> extends LinstorEvent<T>
{
    void triggerEvent(ObjectIdentifier objectIdentifier, T value);

    void closeStream(ObjectIdentifier objectIdentifier);

    void closeStreamNoConnection(ObjectIdentifier objectIdentifier);

    default void forwardEvent(ObjectIdentifier objectIdentifier, String eventStreamAction, T value)
    {
        switch (eventStreamAction)
        {
            case ApiConsts.EVENT_STREAM_VALUE:
                triggerEvent(objectIdentifier, value);
                break;
            case ApiConsts.EVENT_STREAM_CLOSE_REMOVED:
                closeStream(objectIdentifier);
                break;
            case ApiConsts.EVENT_STREAM_CLOSE_NO_CONNECTION:
                closeStreamNoConnection(objectIdentifier);
                break;
            default:
                throw new ImplementationError("Unknown event action '" + eventStreamAction + "'");
        }
    }
}
