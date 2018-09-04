package com.linbit.linstor.core.apicallhandler.controller;

import com.linbit.ImplementationError;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.Node;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.annotation.PeerContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiCallRcImpl;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.core.apicallhandler.response.ApiRcException;
import com.linbit.linstor.core.apicallhandler.response.ResponseUtils;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.netcom.PeerNotConnectedException;
import com.linbit.linstor.proto.LinStorMapEntryOuterClass;
import com.linbit.linstor.proto.MsgApiCallResponseOuterClass;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Signal;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Notifies satellites of updates, returning the responses from the deployment of these changes.
 * When failures occur (either due to connection problems or deployment failures) they are converted into responses
 * and a {@link DelayedApiRcException} is emitted after all the updates have terminated.
 */
@Singleton
public class CtrlSatelliteUpdateCaller
{
    private final AccessContext apiCtx;
    private final CtrlStltSerializer internalComSerializer;

    @Inject
    private CtrlSatelliteUpdateCaller(
        ErrorReporter errorReporterRef,
        @ApiContext AccessContext apiCtxRef,
        CtrlStltSerializer serializerRef,
        @PeerContext Provider<AccessContext> peerAccCtxRef,
        Provider<Peer> peerRef
    )
    {
        apiCtx = apiCtxRef;
        internalComSerializer = serializerRef;
    }

    /**
     * See {@link CtrlSatelliteUpdateCaller}.
     */
    public Flux<Tuple2<NodeName, ApiCallRc>> updateSatellites(Resource rsc)
    {
        return updateSatellites(rsc.getDefinition());
    }

    /**
     * See {@link CtrlSatelliteUpdateCaller}.
     */
    public Flux<Tuple2<NodeName, ApiCallRc>> updateSatellites(ResourceDefinition rscDfn)
    {
        List<Tuple2<NodeName, Flux<ApiCallRc>>> responses = new ArrayList<>();

        try
        {
            // notify all peers that one of their resources has changed
            Iterator<Resource> rscIterator = rscDfn.iterateResource(apiCtx);
            while (rscIterator.hasNext())
            {
                Resource currentRsc = rscIterator.next();

                Flux<ApiCallRc> response = updateResource(currentRsc);

                responses.add(Tuples.of(currentRsc.getAssignedNode().getName(), response));
            }
        }
        catch (AccessDeniedException implError)
        {
            throw new ImplementationError(implError);
        }

        return mergeExtractingApiRcExceptions(Flux.fromIterable(responses));
    }

    private Flux<ApiCallRc> updateResource(Resource currentRsc)
        throws AccessDeniedException
    {
        Node node = currentRsc.getAssignedNode();
        NodeName nodeName = node.getName();

        Flux<ApiCallRc> response;
        Peer currentPeer = node.getPeer(apiCtx);

        if (currentPeer.isConnected() && currentPeer.hasFullSyncFailed())
        {
            response = Flux.error(new ApiRcException(ResponseUtils.makeFullSyncFailedResponse(currentPeer)));
        }
        else
        {
            response = currentPeer
                .apiCall(
                    InternalApiConsts.API_CHANGED_RSC,
                    internalComSerializer
                        .headerlessBuilder()
                        .changedResource(
                            currentRsc.getUuid(),
                            currentRsc.getDefinition().getName().displayValue
                        )
                        .build()
                )

                .map(inputStream -> deserializeApiCallRc(nodeName, inputStream))

                .onErrorMap(PeerNotConnectedException.class, ignored ->
                    new ApiRcException(ResponseUtils.makeNotConnectedWarning(nodeName))
                );
        }

        return response;
    }

    private ApiCallRc deserializeApiCallRc(NodeName nodeName, ByteArrayInputStream inputStream)
    {
        ApiCallRcImpl deploymentState = new ApiCallRcImpl();

        try
        {
            while (inputStream.available() > 0)
            {
                MsgApiCallResponseOuterClass.MsgApiCallResponse apiCallResponse =
                    MsgApiCallResponseOuterClass.MsgApiCallResponse.parseDelimitedFrom(inputStream);
                ApiCallRcImpl.ApiCallRcEntry entry = new ApiCallRcImpl.ApiCallRcEntry();
                entry.setReturnCode(apiCallResponse.getRetCode());
                entry.setMessage(
                    "(" + nodeName.displayValue + ") " + apiCallResponse.getMessage()
                );
                entry.setCause(apiCallResponse.getCause());
                entry.setCorrection(apiCallResponse.getCorrection());
                entry.setDetails(apiCallResponse.getDetails());
                entry.putAllObjRef(readLinStorMap(apiCallResponse.getObjRefsList()));
                deploymentState.addEntry(entry);
            }
        }
        catch (IOException exc)
        {
            throw new ImplementationError(exc);
        }

        return deploymentState;
    }

    private Map<String, String> readLinStorMap(List<LinStorMapEntryOuterClass.LinStorMapEntry> linStorMap)
    {
        return linStorMap.stream()
            .collect(Collectors.toMap(
                LinStorMapEntryOuterClass.LinStorMapEntry::getKey,
                LinStorMapEntryOuterClass.LinStorMapEntry::getValue
            ));
    }

    /**
     * Merge the sources, delaying failure.
     * Any {@link ApiRcException} errors are suppressed and converted into normal responses.
     * If any errors were suppressed, a token {@link DelayedApiRcException} error is emitted when all sources complete.
     */
    private static Flux<Tuple2<NodeName, ApiCallRc>> mergeExtractingApiRcExceptions(
        Publisher<Tuple2<NodeName, ? extends Publisher<ApiCallRc>>> sources)
    {
        return Flux
            .merge(
                Flux.from(sources)
                    .map(source ->
                        Flux.from(source.getT2())
                            .map(Signal::next)
                            .onErrorResume(ApiRcException.class, error -> Flux.just(Signal.error(error)))
                            .map(signal -> Tuples.of(source.getT1(), signal))
                    )
            )
            .compose(signalFlux ->
                {
                    AtomicBoolean hasError = new AtomicBoolean();
                    return signalFlux
                        .map(namedSignal ->
                            {
                                Signal<ApiCallRc> signal = namedSignal.getT2();
                                ApiCallRc apiCallRc;
                                if (signal.isOnError())
                                {
                                    hasError.set(true);
                                    ApiRcException apiRcException = (ApiRcException) signal.getThrowable();
                                    apiCallRc = apiRcException.getApiCallRc();
                                }
                                else
                                {
                                    apiCallRc = signal.get();
                                }
                                return Tuples.of(namedSignal.getT1(), apiCallRc);
                            }
                        )
                        .concatWith(Flux.defer(() ->
                            hasError.get() ?
                                Flux.error(new DelayedApiRcException()) :
                                Flux.empty()
                        ));
                }
            );
    }

    /**
     * See {@link CtrlSatelliteUpdateCaller}.
     */
    public static class DelayedApiRcException extends RuntimeException
    {
        public DelayedApiRcException()
        {
            super("Exceptions have been converted to responses");
        }
    }
}