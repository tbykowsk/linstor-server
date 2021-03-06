package com.linbit.linstor.api.protobuf.satellite;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.api.ApiCallReactive;
import com.linbit.linstor.api.protobuf.ProtobufApiCall;
import com.linbit.linstor.core.ControllerPeerConnector;
import com.linbit.linstor.core.DeviceManager;
import com.linbit.linstor.core.apicallhandler.ResponseSerializer;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.proto.javainternal.IntObjectIdOuterClass.IntObjectId;
import reactor.core.publisher.Flux;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@ProtobufApiCall(
    name = InternalApiConsts.API_CHANGED_RSC,
    description = "Called by the controller to indicate that a resource was modified"
)
@Singleton
public class ChangedRsc implements ApiCallReactive
{
    private final DeviceManager deviceManager;
    private final ControllerPeerConnector controllerPeerConnector;
    private final ResponseSerializer responseSerializer;

    @Inject
    public ChangedRsc(
        DeviceManager deviceManagerRef,
        ControllerPeerConnector controllerPeerConnectorRef,
        ResponseSerializer responseSerializerRef
    )
    {
        deviceManager = deviceManagerRef;
        controllerPeerConnector = controllerPeerConnectorRef;
        responseSerializer = responseSerializerRef;
    }

    @Override
    public Flux<byte[]> executeReactive(InputStream msgDataIn)
        throws IOException
    {
        IntObjectId rscId = IntObjectId.parseDelimitedFrom(msgDataIn);
        String rscNameStr = rscId.getName();
        UUID rscUuid = UUID.fromString(rscId.getUuid());

        ResourceName rscName;
        try
        {
            rscName = new ResourceName(rscNameStr);
        }
        catch (InvalidNameException invalidNameExc)
        {
            throw new ImplementationError(
                "Controller sent an illegal resource name: " + rscNameStr + ".",
                invalidNameExc
            );
        }

        return deviceManager.getUpdateTracker()
            .updateResource(
                rscUuid,
                rscName,
                controllerPeerConnector.getLocalNodeName()
            )
            .transform(responseSerializer::transform);
    }
}
