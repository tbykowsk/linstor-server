package com.linbit.linstor.core.objects;

import com.linbit.linstor.AccessToDeletedDataException;
import com.linbit.linstor.DbgInstanceUuid;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.dbdrivers.interfaces.NodeConnectionDatabaseDriver;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.propscon.PropsAccess;
import com.linbit.linstor.propscon.PropsContainer;
import com.linbit.linstor.propscon.PropsContainerFactory;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.AccessType;
import com.linbit.linstor.transaction.BaseTransactionObject;
import com.linbit.linstor.transaction.TransactionMgr;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.linstor.transaction.TransactionSimpleObject;

import javax.inject.Provider;

import java.util.Arrays;
import java.util.UUID;

/**
 * Defines a connection between two LinStor nodes
 *
 * @author Gabor Hernadi &lt;gabor.hernadi@linbit.com&gt;
 */
public class NodeConnection extends BaseTransactionObject
    implements DbgInstanceUuid, Comparable<NodeConnection>
{
    // Object identifier
    private final UUID objId;

    // Runtime instance identifier for debug purposes
    private final transient UUID dbgInstanceId;

    private final Node sourceNode;
    private final Node targetNode;

    private final Props props;

    private final NodeConnectionDatabaseDriver dbDriver;

    private final TransactionSimpleObject<NodeConnection, Boolean> deleted;

    NodeConnection(
        UUID uuid,
        Node node1,
        Node node2,
        NodeConnectionDatabaseDriver dbDriverRef,
        PropsContainerFactory propsContainerFactory,
        TransactionObjectFactory transObjFactory,
        Provider<? extends TransactionMgr> transMgrProviderRef
    )
        throws DatabaseException
    {
        super(transMgrProviderRef);

        objId = uuid;
        dbDriver = dbDriverRef;
        dbgInstanceId = UUID.randomUUID();

        if (node1.getName().compareTo(node2.getName()) < 0)
        {
            sourceNode = node1;
            targetNode = node2;
        }
        else
        {
            sourceNode = node2;
            targetNode = node1;
        }

        props = propsContainerFactory.getInstance(
            PropsContainer.buildPath(
                sourceNode.getName(),
                targetNode.getName()
            )
        );
        deleted = transObjFactory.createTransactionSimpleObject(this, false, null);

        transObjs = Arrays.asList(
            sourceNode,
            targetNode,
            props,
            deleted
        );
    }

    public static NodeConnection get(
        AccessContext accCtx,
        Node node1,
        Node node2
    )
        throws AccessDeniedException
    {
        Node source;
        Node target;
        if (node1.getName().compareTo(node2.getName()) < 0)
        {
            source = node1;
            target = node2;
        }
        else
        {
            source = node2;
            target = node1;
        }

        return source.getNodeConnection(accCtx, target);
    }


    @Override
    public UUID debugGetVolatileUuid()
    {
        return dbgInstanceId;
    }

    public UUID getUuid()
    {
        checkDeleted();
        return objId;
    }

    public Node getSourceNode(AccessContext accCtx) throws AccessDeniedException
    {
        checkDeleted();
        sourceNode.getObjProt().requireAccess(accCtx, AccessType.VIEW);
        return sourceNode;
    }

    public Node getTargetNode(AccessContext accCtx) throws AccessDeniedException
    {
        checkDeleted();
        sourceNode.getObjProt().requireAccess(accCtx, AccessType.VIEW);
        return targetNode;
    }

    public Props getProps(AccessContext accCtx) throws AccessDeniedException
    {
        checkDeleted();
        return PropsAccess.secureGetProps(
            accCtx,
            sourceNode.getObjProt(),
            targetNode.getObjProt(),
            props
        );
    }

    public void delete(AccessContext accCtx) throws AccessDeniedException, DatabaseException
    {
        if (!deleted.get())
        {
            sourceNode.getObjProt().requireAccess(accCtx, AccessType.CHANGE);
            targetNode.getObjProt().requireAccess(accCtx, AccessType.CHANGE);

            sourceNode.removeNodeConnection(accCtx, this);
            targetNode.removeNodeConnection(accCtx, this);

            props.delete();

            activateTransMgr();
            dbDriver.delete(this);

            deleted.set(true);
        }
    }

    private void checkDeleted()
    {
        if (deleted.get())
        {
            throw new AccessToDeletedDataException("Access to deleted node connection");
        }
    }

    @Override
    public int compareTo(NodeConnection other)
    {
        return (sourceNode.getName().value + targetNode.getName().value).compareTo(
            other.sourceNode.getName().value + targetNode.getName().value);
    }

    @Override
    public String toString()
    {
        return "Node1: '" + sourceNode.getName() + "', " +
               "Node2: '" + targetNode.getName() + "'";
    }
}
