package com.linbit.linstor.drbdstate;

import com.linbit.ImplementationError;
import com.linbit.linstor.MinorNumber;
import com.linbit.linstor.core.DrbdStateChange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Multiplexes state changes on DRBD resources to ResourceObserver instances
 *
 * @author Robert Altnoeder &lt;robert.altnoeder@linbit.com&gt;
 */
public class StateTracker
{
    // Observe resource creation
    public static final long OBS_RES_CRT    = 0x1;

    // Observe resource destruction
    public static final long OBS_RES_DSTR   = 0x2;

    // Observer resource role change
    public static final long OBS_ROLE       = 0x4;

    // Observe peer resource / connection role change
    public static final long OBS_PEER_ROLE  = 0x8;

    // Observe volume creation
    public static final long OBS_VOL_CRT    = 0x10;

    // Observe volume destruction
    public static final long OBS_VOL_DSTR   = 0x20;

    // Observe minor number change
    public static final long OBS_MINOR      = 0x40;

    // Observe disk state change
    public static final long OBS_DISK       = 0x80;

    // Observe replication state change
    public static final long OBS_REPL       = 0x100;

    // Observe connection creation
    public static final long OBS_CONN_CRT   = 0x200;

    // Observe connection destruction
    public static final long OBS_CONN_DSTR  = 0x400;

    // Observe connection state change
    public static final long OBS_CONN       = 0x800;

    // Observe everything
    public static final long OBS_ALL        = 0xFFFFFFFFFFFFFFFFL;

    // Observer list table indexes
    private final int OBS_RES_CRT_SLOT;
    private final int OBS_RES_DSTR_SLOT;
    private final int OBS_ROLE_SLOT;
    private final int OBS_PEER_ROLE_SLOT;
    private final int OBS_VOL_CRT_SLOT;
    private final int OBS_VOL_DSTR_SLOT;
    private final int OBS_MINOR_SLOT;
    private final int OBS_DISK_SLOT;
    private final int OBS_REPL_SLOT;
    private final int OBS_CONN_CRT_SLOT;
    private final int OBS_CONN_DSTR_SLOT;
    private final int OBS_CONN_SLOT;

    private int obsSlotCount;
    private Set<ResourceObserver>[] observers;
    private Map<ResourceObserver, Long> obsMaskMap;
    List<DrbdStateChange> drbdStateChangeObservers;

    private final Map<String, DrbdResource> resList;
    protected final ResObsMux multiplexer;

    private final long validEventsMask;

    public StateTracker()
    {
        // Initialize number of observer slots to minimum
        // The number of actually required slots is set by the
        // initialBitToIndex() method during *_SLOT variable
        // initialization
        obsSlotCount = 1;

        // Initialize slot indexes
        OBS_RES_CRT_SLOT     = initBitToSlot(OBS_RES_CRT);
        OBS_RES_DSTR_SLOT    = initBitToSlot(OBS_RES_DSTR);
        OBS_ROLE_SLOT        = initBitToSlot(OBS_ROLE);
        OBS_PEER_ROLE_SLOT   = initBitToSlot(OBS_PEER_ROLE);
        OBS_VOL_CRT_SLOT     = initBitToSlot(OBS_VOL_CRT);
        OBS_VOL_DSTR_SLOT    = initBitToSlot(OBS_VOL_DSTR);
        OBS_MINOR_SLOT       = initBitToSlot(OBS_MINOR);
        OBS_DISK_SLOT        = initBitToSlot(OBS_DISK);
        OBS_REPL_SLOT        = initBitToSlot(OBS_REPL);
        OBS_CONN_CRT_SLOT    = initBitToSlot(OBS_CONN_CRT);
        OBS_CONN_DSTR_SLOT   = initBitToSlot(OBS_CONN_DSTR);
        OBS_CONN_SLOT        = initBitToSlot(OBS_CONN);

        observers = new HashSet[obsSlotCount];
        for (int slot = 0; slot < obsSlotCount; ++slot)
        {
            observers[slot] = new HashSet<>();
        }
        obsMaskMap = new HashMap<>();

        resList = new TreeMap<>();

        // Initialize mask for valid event IDs
        validEventsMask = (1 << obsSlotCount) - 1;

        multiplexer = new ResObsMux(this);

        drbdStateChangeObservers = new ArrayList<>();
    }

    public DrbdResource getResource(String name)
    {
        // Map.get(null) returns null, avoid hiding bugs
        if (name == null)
        {
            throw new ImplementationError(
                "Attempt to obtain a DrbdResource object with name == null",
                new NullPointerException()
            );
        }
        DrbdResource res = null;
        synchronized (resList)
        {
            res = resList.get(name);
        }
        return res;
    }

    void putResource(DrbdResource resource)
    {
        synchronized (resList)
        {
            resList.put(resource.resName, resource);
        }
    }

    DrbdResource removeResource(String name)
    {
        // Map.remove(null) is a valid operation, avoid hiding bugs
        if (name == null)
        {
            throw new ImplementationError(
                "Attempt to remove a DrbdResource object with name == null",
                new NullPointerException()
            );
        }
        DrbdResource removedRes = null;
        synchronized (resList)
        {
            removedRes = resList.remove(name);
        }
        return removedRes;
    }

    private int initBitToSlot(long eventId)
    {
        int slot = StateTracker.bitToSlot(eventId);
        if (slot > obsSlotCount)
        {
            obsSlotCount = slot;
        }
        ++obsSlotCount;
        return slot;
    }

    private static int bitToSlot(long eventId)
    {
        int slot = 0;
        for (long value = eventId >>> 1; value != 0; value >>>= 1)
        {
            ++slot;
        }
        return slot;
    }

    public void addObserver(ResourceObserver obs, long eventMask)
    {
        // Mask out any invalid event IDs
        long safeEventMask = eventMask & validEventsMask;

        if (safeEventMask != 0)
        {
            obsMaskMap.put(obs, safeEventMask);
            // Register the ResourceObserver for all selected event types
            for (long scanMask = 1; safeEventMask != 0; scanMask <<= 1)
            {
                long eventId = safeEventMask & scanMask;
                if (eventId != 0)
                {
                    observers[bitToSlot(eventId)].add(obs);
                }
                safeEventMask &= (~scanMask);
            }
        }
    }

    public void removeObserver(ResourceObserver obs)
    {
        Long eventMask = obsMaskMap.get(obs);
        if (eventMask != null)
        {
            // Remove the ResourceObserver from the list for each selected event type
            for (long scanMask = 1; eventMask != 0; scanMask <<= 1)
            {
                long eventId = eventMask & scanMask;
                if (eventId != 0)
                {
                    observers[bitToSlot(eventId)].remove(obs);
                }
                eventMask &= (~scanMask);
            }
        }
        obsMaskMap.remove(obs);
    }

    public void addDrbdStateChangeObserver(DrbdStateChange obs)
    {
        drbdStateChangeObservers.add(obs);
    }

    /**
     * Event multiplexer
     */
    static class ResObsMux implements ResourceObserver
    {
        private final StateTracker container;

        protected ResObsMux(StateTracker containerRef)
        {
            container = containerRef;
        }

        @Override
        public void resourceCreated(DrbdResource resource)
        {
            for (ResourceObserver obs : container.observers[container.OBS_RES_CRT_SLOT])
            {
                obs.resourceCreated(resource);
            }
        }

        @Override
        public void roleChanged(DrbdResource resource, DrbdResource.Role previous, DrbdResource.Role current)
        {
            for (ResourceObserver obs : container.observers[container.OBS_ROLE_SLOT])
            {
                obs.roleChanged(resource, previous, current);
            }
        }

        @Override
        public void peerRoleChanged(
            DrbdResource resource, DrbdConnection connection,
            DrbdResource.Role previous, DrbdResource.Role current
        )
        {
            for (ResourceObserver obs : container.observers[container.OBS_PEER_ROLE_SLOT])
            {
                obs.peerRoleChanged(resource, connection, previous, current);
            }
        }

        @Override
        public void resourceDestroyed(DrbdResource resource)
        {
            for (ResourceObserver obs : container.observers[container.OBS_RES_DSTR_SLOT])
            {
                obs.resourceDestroyed(resource);
            }
        }

        @Override
        public void volumeCreated(DrbdResource resource, DrbdConnection connection, DrbdVolume volume)
        {
            for (ResourceObserver obs : container.observers[container.OBS_VOL_CRT_SLOT])
            {
                obs.volumeCreated(resource, connection, volume);
            }
        }

        @Override
        public void minorNrChanged(
            DrbdResource resource, DrbdVolume volume,
            MinorNumber previous, MinorNumber current
        )
        {
            for (ResourceObserver obs : container.observers[container.OBS_MINOR_SLOT])
            {
                obs.minorNrChanged(resource, volume, previous, current);
            }
        }

        @Override
        public void diskStateChanged(
            DrbdResource resource, DrbdConnection connection, DrbdVolume volume,
            DrbdVolume.DiskState previous, DrbdVolume.DiskState current
        )
        {
            for (ResourceObserver obs : container.observers[container.OBS_DISK_SLOT])
            {
                obs.diskStateChanged(resource, connection, volume, previous, current);
            }
        }

        @Override
        public void replicationStateChanged(
            DrbdResource resource, DrbdConnection connection, DrbdVolume volume,
            DrbdVolume.ReplState previous, DrbdVolume.ReplState current
        )
        {
            for (ResourceObserver obs : container.observers[container.OBS_REPL_SLOT])
            {
                obs.replicationStateChanged(resource, connection, volume, previous, current);
            }
        }

        @Override
        public void volumeDestroyed(DrbdResource resource, DrbdVolume volume)
        {
            for (ResourceObserver obs : container.observers[container.OBS_VOL_DSTR_SLOT])
            {
                obs.volumeDestroyed(resource, volume);
            }
        }

        @Override
        public void connectionCreated(DrbdResource resource, DrbdConnection connection)
        {
            for (ResourceObserver obs : container.observers[container.OBS_CONN_CRT_SLOT])
            {
                obs.connectionCreated(resource, connection);
            }
        }

        @Override
        public void connectionStateChanged(
            DrbdResource resource, DrbdConnection connection,
            DrbdConnection.State previous, DrbdConnection.State current
        )
        {
            for (ResourceObserver obs : container.observers[container.OBS_CONN_SLOT])
            {
                obs.connectionStateChanged(resource, connection, previous, current);
            }
        }

        @Override
        public void connectionDestroyed(DrbdResource resource, DrbdConnection connection)
        {
            for (ResourceObserver obs : container.observers[container.OBS_CONN_DSTR_SLOT])
            {
                obs.connectionDestroyed(resource, connection);
            }
        }
    }

    public static void main(String[] argv)
    {
        StateTracker instance = new StateTracker();
        instance.debugOutput();
    }

    public void debugOutput()
    {
        System.out.printf(
            "OBS_RES_CRT_SLOT    = %d\n" +
            "OBS_RES_DSTR_SLOT   = %d\n" +
            "OBS_ROLE_SLOT       = %d\n" +
            "OBS_PEER_ROLE_SLOT  = %d\n" +
            "OBS_VOL_CRT_SLOT    = %d\n" +
            "OBS_VOL_DSTR_SLOT   = %d\n" +
            "OBS_MINOR_SLOT      = %d\n" +
            "OBS_DISK_SLOT       = %d\n" +
            "OBS_REPL_SLOT       = %d\n" +
            "OBS_CONN_CRT_SLOT   = %d\n" +
            "OBS_CONN_DSTR_SLOT  = %d\n" +
            "OBS_CONN_SLOT       = %d\n",
            OBS_RES_CRT_SLOT,
            OBS_RES_DSTR_SLOT,
            OBS_ROLE_SLOT,
            OBS_PEER_ROLE_SLOT,
            OBS_VOL_CRT_SLOT,
            OBS_VOL_DSTR_SLOT,
            OBS_MINOR_SLOT,
            OBS_DISK_SLOT,
            OBS_REPL_SLOT,
            OBS_CONN_CRT_SLOT,
            OBS_CONN_DSTR_SLOT,
            OBS_CONN_SLOT
        );

        System.out.printf(
            "OBS_RES_CRT         = %08x\n" +
            "OBS_RES_DSTR        = %08x\n" +
            "OBS_ROLE            = %08x\n" +
            "OBS_PEER_ROLE       = %08x\n" +
            "OBS_VOL_CRT         = %08x\n" +
            "OBS_VOL_DSTR        = %08x\n" +
            "OBS_MINOR           = %08x\n" +
            "OBS_DISK            = %08x\n" +
            "OBS_REPL            = %08x\n" +
            "OBS_CONN_CRT        = %08x\n" +
            "OBS_CONN_DSTR       = %08x\n" +
            "OBS_CONN            = %08x\n",
            OBS_RES_CRT,
            OBS_RES_DSTR,
            OBS_ROLE,
            OBS_PEER_ROLE,
            OBS_VOL_CRT,
            OBS_VOL_DSTR,
            OBS_MINOR,
            OBS_DISK,
            OBS_REPL,
            OBS_CONN_CRT,
            OBS_CONN_DSTR,
            OBS_CONN
        );

        System.out.printf("obsSlotCount = %d, observers.length = %d\n", obsSlotCount, observers.length);
        System.out.printf("validEventsMask = %08x\n", validEventsMask);
    }
}
