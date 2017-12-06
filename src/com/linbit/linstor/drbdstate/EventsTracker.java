package com.linbit.linstor.drbdstate;

import com.linbit.ImplementationError;
import com.linbit.ValueOutOfRangeException;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.core.DrbdStateChange;

import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

/**
 * Drbdsetup events tracker
 *
 * Interprets the 'drbdsetup events2 all' event lines and updates/triggers the
 * DRBD state tracker
 *
 * @author Rene Blauensteiner &lt;rene.blauensteiner@linbit.com&gt;
 * @author Robert Altnoeder &lt;robert.altnoeder@linbit.com&gt;
 */
public class EventsTracker
{
    public static final String ACTION_CREATE    = "create";
    public static final String ACTION_CHANGE    = "change";
    public static final String ACTION_DESTROY   = "destroy";
    public static final String ACTION_EXISTS    = "exists";

    public static final String OBJ_RESOURCE     = "resource";
    public static final String OBJ_VOLUME       = "device";
    public static final String OBJ_PEER_VOLUME  = "peer-device";
    public static final String OBJ_CONNECTION   = "connection";
    public static final String OBJ_END_OF_INIT  = "-";

    // DRBD state tracker & events multiplexer reference
    private final StateTracker tracker;
    private boolean stateAvailable = false;

    public EventsTracker(StateTracker trackerRef)
    {
        tracker = trackerRef;
    }

    public void receiveEvent(String eventString) throws EventsSourceException
    {
        if (eventString == null)
        {
            throw new ImplementationError(
                "Event string passed by caller is a null pointer",
                new NullPointerException()
            );
        }

        // Skip empty lines
        if (!eventString.isEmpty())
        {
            StringTokenizer tokens = new StringTokenizer(eventString, " ");
            Map<String, String> props = new TreeMap<>();

            if (tokens.hasMoreTokens())
            {
                String action = tokens.nextToken();
                if (tokens.hasMoreTokens())
                {
                    String objType = tokens.nextToken();
                    while (tokens.hasMoreTokens())
                    {
                        String kvPair = tokens.nextToken();
                        int splitIdx = kvPair.indexOf(':');
                        if (splitIdx != -1)
                        {
                            String key = kvPair.substring(0, splitIdx);
                            String value = kvPair.substring(splitIdx + 1);
                            props.put(key, value);
                        }
                    }

                    // Select action
                    switch (action)
                    {
                        case ACTION_EXISTS: // fall-through
                        case ACTION_CREATE:
                            create(props, objType);
                            break;
                        case ACTION_CHANGE:
                            change(props, objType);
                            break;
                        case ACTION_DESTROY:
                            destroy(props, objType);
                            break;
                        default:
                            // Other action type, such as a helper script call
                            // Those are not tracked
                            break;
                    }
                }
                else
                {
                    throw new EventsSourceException("Received an event line without an object type parameter");
                }
            }
            else
            {
                throw new EventsSourceException("Received an event line without an action parameter");
            }
        }
    }

    private void create(Map<String, String> props, String object) throws EventsSourceException
    {
        switch (object)
        {
            case OBJ_RESOURCE:
                createResource(props);
                break;
            case OBJ_CONNECTION:
                createConnection(props);
                break;
            case OBJ_VOLUME:
                createVolume(props);
                break;
            case OBJ_PEER_VOLUME:
                createPeerVolume(props);
                break;
            case OBJ_END_OF_INIT:
                drbdStateAvailable();
                break;
            default:
                // Other object type, such as a connection path
                // Those types are currently ignored
                break;
        }
    }

    public void reinitializing()
    {
        stateAvailable = false;
        for (DrbdStateChange obs : tracker.drbdStateChangeObservers)
        {
            obs.drbdStateUnavailable();
        }
    }

    private void drbdStateAvailable()
    {
        stateAvailable = true;
        for (DrbdStateChange obs : tracker.drbdStateChangeObservers)
        {
            obs.drbdStateAvailable();
        }
    }

    public boolean isStateAvailable()
    {
        return stateAvailable;
    }

    private void change(Map<String, String> props, String object) throws EventsSourceException
    {
        switch (object)
        {
            case OBJ_RESOURCE:
                changeResource(props);
                break;
            case OBJ_CONNECTION:
                changeConnection(props);
                break;
            case OBJ_VOLUME:
                changeVolume(props);
                break;
            case OBJ_PEER_VOLUME:
                changePeerVolume(props);
                break;
            default:
                // Other object type, such as a connection path
                // Those types are currently ignored
                break;
        }
    }

    private void destroy(Map<String, String> props, String object) throws EventsSourceException
    {
        switch (object)
        {
            case OBJ_RESOURCE:
                destroyResource(props);
                break;
            case OBJ_CONNECTION:
                destroyConnection(props);
                break;
            case OBJ_VOLUME:
                destroyVolume(props);
                break;
            case OBJ_PEER_VOLUME:
                destroyPeerVolume(props);
                break;
            default:
                // Other object type, such as a connection path
                // Those types are currently ignored
                break;
        }
    }

    private void createResource(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = DrbdResource.newFromProps(props);
        tracker.putResource(resource);
        tracker.multiplexer.resourceCreated(resource);
        resource.update(props, tracker.multiplexer);
    }

    private void createConnection(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_CREATE, OBJ_CONNECTION);
        DrbdConnection connection = DrbdConnection.newFromProps(resource, props);
        resource.putConnection(connection);
        tracker.multiplexer.connectionCreated(resource, connection);
        connection.update(props, tracker.multiplexer);
    }

    private void createVolume(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_CREATE, OBJ_VOLUME);
        DrbdVolume volume = DrbdVolume.newFromProps(resource, props);
        resource.putVolume(volume);
        tracker.multiplexer.volumeCreated(resource, null, volume);
        volume.update(props, tracker.multiplexer);
    }

    private void createPeerVolume(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_CREATE, OBJ_PEER_VOLUME);
        DrbdConnection connection = getConnection(resource, props, ACTION_CREATE, OBJ_PEER_VOLUME);
        DrbdVolume volume = DrbdVolume.newFromProps(resource, props);
        connection.putVolume(volume);
        tracker.multiplexer.volumeCreated(resource, connection, volume);
        volume.update(props, tracker.multiplexer);
    }

    private void changeResource(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_CHANGE, OBJ_RESOURCE);
        resource.update(props, tracker.multiplexer);
    }

    private void changeConnection(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_CHANGE, OBJ_CONNECTION);
        DrbdConnection connection = getConnection(resource, props, ACTION_CHANGE, OBJ_CONNECTION);
        connection.update(props, tracker.multiplexer);
    }

    private void changeVolume(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_CHANGE, OBJ_VOLUME);
        DrbdVolume volume = getVolume(resource, null, props, ACTION_CHANGE, OBJ_VOLUME);
        volume.update(props, tracker.multiplexer);
    }

    private void changePeerVolume(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_CHANGE, OBJ_PEER_VOLUME);
        DrbdConnection connection = getConnection(resource, props, ACTION_CHANGE, OBJ_PEER_VOLUME);
        DrbdVolume volume = getVolume(resource, connection, props, ACTION_CHANGE, OBJ_PEER_VOLUME);
        volume.update(props, tracker.multiplexer);
    }

    private void destroyResource(Map<String, String> props) throws EventsSourceException
    {
        String resName = getProp(props, DrbdResource.PROP_KEY_RES_NAME, ACTION_DESTROY, OBJ_RESOURCE);
        if (tracker.removeResource(resName) == null)
        {
            nonExistentResource(ACTION_DESTROY, OBJ_RESOURCE, resName);
        }
    }

    private void destroyConnection(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_DESTROY, OBJ_CONNECTION);
        String connName = getProp(props, DrbdConnection.PROP_KEY_CONN_NAME, ACTION_DESTROY, OBJ_CONNECTION);
        if (resource.removeConnection(connName) == null)
        {
            nonExistentConnection(ACTION_DESTROY, OBJ_CONNECTION, resource, connName);
        }
    }

    private void destroyVolume(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_DESTROY, OBJ_VOLUME);
        VolumeNumber volNr = getVolumeNr(props, ACTION_DESTROY, OBJ_VOLUME);
        if (resource.removeVolume(volNr) == null)
        {
            nonExistentVolume(ACTION_DESTROY, OBJ_VOLUME, resource, null, volNr);
        }
    }

    private void destroyPeerVolume(Map<String, String> props) throws EventsSourceException
    {
        DrbdResource resource = getResource(props, ACTION_DESTROY, OBJ_PEER_VOLUME);
        DrbdConnection connection = getConnection(resource, props, ACTION_DESTROY, OBJ_PEER_VOLUME);
        VolumeNumber volNr = getVolumeNr(props, ACTION_DESTROY, OBJ_PEER_VOLUME);
        if (connection.removeVolume(volNr) == null)
        {
            nonExistentVolume(ACTION_DESTROY, OBJ_PEER_VOLUME, resource, connection, volNr);
        }
    }

    private DrbdResource getResource(Map<String, String> props, String action, String objType)
        throws EventsSourceException
    {
        String resName = getProp(props, DrbdResource.PROP_KEY_RES_NAME, action, objType);
        DrbdResource res = tracker.getResource(resName);
        if (res == null)
        {
            nonExistentResource(ACTION_DESTROY, OBJ_PEER_VOLUME, resName);
        }
        return res;
    }

    private DrbdConnection getConnection(
        DrbdResource resource,
        Map<String, String> props,
        String action,
        String objType
    )
        throws EventsSourceException
    {
        String connName = getProp(props, DrbdConnection.PROP_KEY_CONN_NAME, action, objType);
        DrbdConnection conn = resource.getConnection(connName);
        if (conn == null)
        {
            nonExistentConnection(ACTION_DESTROY, OBJ_CONNECTION, resource, connName);
        }
        return conn;
    }

    private DrbdVolume getVolume(
        DrbdResource resource,
        DrbdConnection connection,
        Map<String, String> props,
        String action,
        String objType
    )
        throws EventsSourceException
    {
        VolumeNumber volNr = getVolumeNr(props, action, objType);

        DrbdVolume volume = null;
        if (connection == null)
        {
            volume = resource.getVolume(volNr);
        }
        else
        {
            volume = connection.getVolume(volNr);
        }
        if (volume == null)
        {
            nonExistentVolume(action, objType, resource, connection, volNr);
        }
        return volume;
    }

    private static String getProp(
        Map<String, String> props,
        String propKey,
        String action,
        String objType
    )
        throws EventsSourceException
    {
        String propValue = props.get(propKey);
        if (propValue == null)
        {
            throw new EventsSourceException(
                String.format(
                    "Event line for operation '%s %s' does not contain the '%s' argument",
                    action, objType, propKey
                )
            );
        }
        return propValue;
    }

    private static VolumeNumber getVolumeNr(
        Map<String, String> props,
        String action,
        String objType
    )
        throws EventsSourceException
    {
        String volNrText = getProp(props, DrbdVolume.PROP_KEY_VOL_NR, action, objType);
        int parsedNumber;
        VolumeNumber volNr = null;
        try
        {
            parsedNumber = Integer.parseInt(volNrText);
            volNr = new VolumeNumber(parsedNumber);
        }
        catch (NumberFormatException | ValueOutOfRangeException exc)
        {
            throw new EventsSourceException(
                String.format(
                    "Event line for operation '%s %s' contains an invalid volume number",
                    action, objType
                ),
                exc
            );
        }
        return volNr;
    }

    private void nonExistentResource(
        String action,
        String objType,
        String resName
    )
        throws EventsSourceException
    {
        throw new EventsSourceException(
        String.format(
            "Event line for operation '%s %s' references non-existent resource '%s'",
            action, objType, resName
            )
        );
    }

    private void nonExistentConnection(
        String action,
        String objType,
        DrbdResource resource,
        String connName
    )
        throws EventsSourceException
    {
        throw new EventsSourceException(
        String.format(
            "Event line for operation '%s %s' references non-existent connection '%s' of resource '%s'",
            action, objType, connName, resource.resName
            )
        );
    }

    private void nonExistentVolume(
        String action,
        String objType,
        DrbdResource resource,
        DrbdConnection connection,
        VolumeNumber volNr
    )
        throws EventsSourceException
    {
        if (connection == null)
        {
            throw new EventsSourceException(
                String.format(
                    "Event line for operation '%s %s' references non-existent volume %d of resource '%s'",
                    action, objType, volNr, resource.resName
                )
            );
        }
        else
        {
            throw new EventsSourceException(
                String.format(
                    "Event line for operation '%s %s' references non-existent peer-volume %d " +
                        "of connection '%s' of resource '%s'",
                        action, objType, volNr, connection.peerName, resource.resName
                )
            );
        }
    }
}
