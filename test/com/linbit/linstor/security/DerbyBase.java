package com.linbit.linstor.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;

import com.linbit.InvalidNameException;
import com.linbit.TransactionMgr;
import com.linbit.linstor.NetInterfaceName;
import com.linbit.linstor.Node;
import com.linbit.linstor.NodeId;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.StorPoolDefinition;
import com.linbit.linstor.StorPoolName;
import com.linbit.linstor.VolumeNumber;
import com.linbit.linstor.Node.NodeType;
import com.linbit.linstor.ResourceDefinition.RscDfnFlags;
import com.linbit.linstor.Volume.VlmFlags;
import com.linbit.linstor.core.CoreUtils;
import com.linbit.linstor.dbcp.DbConnectionPool;
import com.linbit.linstor.dbdrivers.DerbyDriver;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.logging.StdErrorReporter;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.security.DbDerbyPersistence;
import com.linbit.linstor.security.Identity;
import com.linbit.linstor.security.Privilege;
import com.linbit.linstor.security.PrivilegeSet;
import com.linbit.linstor.security.Role;
import com.linbit.linstor.security.SecurityLevel;
import com.linbit.linstor.security.SecurityType;
import com.linbit.linstor.stateflags.StateFlagsBits;
import com.linbit.utils.UuidUtils;

public abstract class DerbyBase implements DerbyConstants
{
    @Rule
    public TestName testMethodName = new TestName();

    private static final String SELECT_PROPS_BY_INSTANCE =
        " SELECT " + PROPS_INSTANCE + ", " + PROP_KEY + ", " + PROP_VALUE +
        " FROM " + TBL_PROPS_CONTAINERS +
        " WHERE " + PROPS_INSTANCE + " = ? " +
        " ORDER BY " + PROP_KEY;

    private static final String DB_URL = "jdbc:derby:memory:testDB";
    private static final String DB_USER = "linstor";
    private static final String DB_PASSWORD = "linbit";
    private static final Properties DB_PROPS = new Properties();

    private List<Statement> statements = new ArrayList<>();
    private static Connection con;
    private static DbConnectionPool dbConnPool;
    private static List<Connection> connections = new ArrayList<>();

    protected static final AccessContext sysCtx;
    private static boolean initialized = false;
    private static DbDerbyPersistence secureDbDriver;
    private static DerbyDriver persistenceDbDriver;
    protected static HashMap<NodeName, Node> nodesMap;
    protected static HashMap<ResourceName, ResourceDefinition> resDfnMap;
    protected static HashMap<StorPoolName, StorPoolDefinition> storPoolDfnMap;

    protected static ErrorReporter errorReporter =
//        new EmptyErrorReporter(true);
        new StdErrorReporter("TESTS");

    static
    {
        PrivilegeSet sysPrivs = new PrivilegeSet(Privilege.PRIV_SYS_ALL);

        sysCtx = new AccessContext(
            Identity.SYSTEM_ID,
            Role.SYSTEM_ROLE,
            SecurityType.SYSTEM_TYPE,
            sysPrivs
        );
        try
        {
            sysCtx.privEffective.enablePrivileges(Privilege.PRIV_SYS_ALL);
        }
        catch (AccessDeniedException iAmNotRootExc)
        {
            throw new RuntimeException(iAmNotRootExc);
        }
    }

    public DerbyBase()
    {
        if (!initialized)
        {
            try
            {
                createTables();
                insertDefaults();

                Identity.load(dbConnPool, secureDbDriver);
                SecurityType.load(dbConnPool, secureDbDriver);
                Role.load(dbConnPool, secureDbDriver);
            }
            catch (SQLException | InvalidNameException exc)
            {
                throw new RuntimeException(exc);
            }


            initialized = true;
        }
    }

    @BeforeClass
    public static void setUpBeforeClass() throws SQLException
    {
        if (dbConnPool == null)
        {
            // load the clientDriver...
            DB_PROPS.setProperty("create", "true");
            DB_PROPS.setProperty("user", DB_USER);
            DB_PROPS.setProperty("password", DB_PASSWORD);

            dbConnPool = new DbConnectionPool();
            dbConnPool.initializeDataSource(DB_URL, DB_PROPS);

            con = dbConnPool.getConnection();
            secureDbDriver = new DbDerbyPersistence(sysCtx, errorReporter);

            nodesMap = new HashMap<NodeName, Node>();
            resDfnMap = new HashMap<ResourceName, ResourceDefinition>();
            storPoolDfnMap = new HashMap<StorPoolName, StorPoolDefinition>();

            persistenceDbDriver = new DerbyDriver(
                sysCtx,
                errorReporter,
                nodesMap,
                resDfnMap,
                storPoolDfnMap
            );
        }
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception
    {
//        dropTables();
//        File dbFolder = new File(DB_FOLDER);
//        deleteFolder(dbFolder);

//        con.close();
//        dbConnPool.shutdown();
//        initialized = false;
    }

    @Before
    public void setUp() throws Exception
    {
        errorReporter.logTrace("Running cleanups for next method: %s", testMethodName.getMethodName());
        truncateTables();
        insertDefaults();

        Connection tmpCon = dbConnPool.getConnection();
        // make sure to seal the internal caches
        CoreUtils.setDatabaseClasses(
            secureDbDriver,
            persistenceDbDriver
        );
        persistenceDbDriver.loadAll(new TransactionMgr(tmpCon));
        tmpCon.close();

        clearCaches();

        errorReporter.logTrace("cleanups done, running method: %s", testMethodName.getMethodName());
    }

    protected void clearCaches()
    {
        nodesMap.clear();
        resDfnMap.clear();
        storPoolDfnMap.clear();
    }

    protected void setSecurityLevel(SecurityLevel level) throws AccessDeniedException, SQLException
    {
        SecurityLevel.set(sysCtx, level, dbConnPool, secureDbDriver);
    }

    protected void satelliteMode()
    {
        CoreUtils.satelliteMode(sysCtx, nodesMap, resDfnMap, storPoolDfnMap);
    }

    @After
    public void tearDown() throws SQLException
    {
        for (Statement statement : statements)
        {
            statement.close();
        }
        for (Connection connection : connections)
        {
            connection.close();
        }
        connections.clear();
    }

    protected static Connection getConnection() throws SQLException
    {
        Connection connection = dbConnPool.getConnection();
        connection.setAutoCommit(false);
        connections.add(connection);
        return connection;
    }

    protected void add(Statement stmt)
    {
        statements.add(stmt);
    }

    private void createTables() throws SQLException
    {
        for (int idx = 0; idx < CREATE_TABLES.length; ++idx)
        {
            createTable(con, true, idx);
        }
        con.commit();
    }

    private static void insertDefaults() throws SQLException
    {
        for (String insert : INSERT_DEFAULT_VALUES)
        {
            try (PreparedStatement stmt = con.prepareStatement(insert))
            {
                stmt.executeUpdate();
            }
        }
        con.commit();
    }

//    private static void dropTables() throws SQLException
//    {
//        for (int idx = 0; idx < DROP_TABLES.length; ++idx)
//        {
//            dropTable(con, idx);
//        }
//    }

    private static void truncateTables() throws SQLException
    {
        for (String sql : TRUNCATE_TABLES)
        {
            PreparedStatement stmt = con.prepareStatement(sql);
            stmt.executeUpdate();
            stmt.close();
        }
    }

    private void createTable(Connection connection, boolean dropIfExists, int idx) throws SQLException
    {
        try
        {
//            System.out.print("creating... " + CREATE_TABLES[idx]);
            try (PreparedStatement stmt = connection.prepareStatement(CREATE_TABLES[idx]))
            {
                stmt.executeUpdate();
//                System.out.println("... done");
            }
        }
        catch (SQLException sqlExc)
        {
            String sqlState = sqlExc.getSQLState();
            if ("X0Y32".equals(sqlState)) // table already exists
            {
                if (dropIfExists)
                {
//                    System.out.print("exists, ");
                    dropTable(connection, DROP_TABLES.length - 1 - idx);
                    createTable(connection, false, idx);
                }
                else
                {
                    System.out.println(CREATE_TABLES[idx]);
                    throw sqlExc;
                }
            }
            else
            {
                System.out.println(CREATE_TABLES[idx]);
                throw sqlExc;
            }
        }
        connection.commit();
    }

    private static void dropTable(Connection connection, int idx) throws SQLException
    {
        try (PreparedStatement stmt = connection.prepareStatement(DROP_TABLES[idx]))
        {
//            System.out.print("dropping... " + DROP_TABLES[idx]);
            stmt.executeUpdate();
//            System.out.println("... done");
        }
        catch (SQLException sqlExc)
        {
            if ("42Y55".equals(sqlExc.getSQLState()))
            {
                // table does not exists.... yay - ignore
            }
            else
            {
                System.out.println(DROP_TABLES[idx]);
                throw sqlExc;
            }
        }
        connection.commit();
    }

    protected String debugGetAllProsContent() throws SQLException
    {
        Connection connection = getConnection();
        PreparedStatement stmt = connection.prepareStatement("SELECT * FROM " + DerbyConstants.TBL_PROPS_CONTAINERS);
        ResultSet allContent = stmt.executeQuery();
        StringBuilder sb = new StringBuilder();
        while (allContent.next())
        {
            sb.append(allContent.getString(1)).append(": ")
                .append(allContent.getString(2)).append(" = ")
                .append(allContent.getString(3)).append("\n");
        }
        allContent.close();
        stmt.close();
        connection.close();
        return sb.toString();
    }

    protected static java.util.UUID randomUUID()
    {
        return java.util.UUID.randomUUID();
    }

    protected static void testProps(
        TransactionMgr transMgr,
        String instanceName,
        Map<String, String> testMap
    )
        throws SQLException
    {
        TreeMap<String, String> map = new TreeMap<>(testMap);
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(SELECT_PROPS_BY_INSTANCE);
        stmt.setString(1, instanceName.toUpperCase());
        ResultSet resultSet = stmt.executeQuery();

        while (resultSet.next())
        {
            String key = resultSet.getString(PROP_KEY);
            String value = resultSet.getString(PROP_VALUE);

            assertEquals(map.remove(key), value);
        }
        assertTrue(map.isEmpty());

        resultSet.close();
        stmt.close();
    }

    protected static void insertObjProt(
        TransactionMgr transMgr,
        String objPath,
        AccessContext accCtx
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_SEC_OBJECT_PROTECTION);
        stmt.setString(1, objPath);
        stmt.setString(2, accCtx.subjectId.name.value);
        stmt.setString(3, accCtx.subjectRole.name.value);
        stmt.setString(4, accCtx.subjectDomain.name.value);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertNode(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName nodeName,
        long flags,
        NodeType... types
    )
        throws SQLException
    {
        long typeMask = 0;
        for (NodeType type : types)
        {
            typeMask |= type.getFlagValue();
        }

        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_NODES);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, nodeName.value);
        stmt.setString(3, nodeName.displayValue);
        stmt.setLong(4, flags);
        stmt.setLong(5, typeMask);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertNetInterface(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName nodeName,
        NetInterfaceName netName,
        String inetAddr,
        String transportType
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_NODE_NET_INTERFACES);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, nodeName.value);
        stmt.setString(3, netName.value);
        stmt.setString(4, netName.displayValue);
        stmt.setString(5, inetAddr);
        stmt.setString(6, transportType);
        stmt.executeUpdate();
        stmt.close();
    }
    protected static void insertNodeCon(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName sourceNodeName,
        NodeName targetNodeName
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_NODE_CONNECTIONS);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, sourceNodeName.value);
        stmt.setString(3, targetNodeName.value);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertResCon(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName sourceNodeName,
        NodeName targetNodeName,
        ResourceName resName
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_RESOURCE_CONNECTIONS);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, sourceNodeName.value);
        stmt.setString(3, targetNodeName.value);
        stmt.setString(4, resName.value);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertVolCon(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName sourceNodeName,
        NodeName targetNodeName,
        ResourceName resName,
        VolumeNumber volDfnNr
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_VOLUME_CONNECTIONS);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, sourceNodeName.value);
        stmt.setString(3, targetNodeName.value);
        stmt.setString(4, resName.value);
        stmt.setInt(5, volDfnNr.value);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertResDfn(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        ResourceName resName,
        RscDfnFlags... flags
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_RESOURCE_DEFINITIONS);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, resName.value);
        stmt.setString(3, resName.displayValue);
        stmt.setLong(4, StateFlagsBits.getMask(flags));
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertRes(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName nodeName,
        ResourceName resName,
        NodeId nodeId,
        Resource.RscFlags... resFlags
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_RESOURCES);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, nodeName.value);
        stmt.setString(3, resName.value);
        stmt.setInt(4, nodeId.value);
        stmt.setLong(5, StateFlagsBits.getMask(resFlags));
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertVolDfn(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        ResourceName resName,
        VolumeNumber volId,
        long volSize,
        int minorNr,
        long flags
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_VOLUME_DEFINITIONS);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, resName.value);
        stmt.setInt(3, volId.value);
        stmt.setLong(4, volSize);
        stmt.setInt(5, minorNr);
        stmt.setLong(6, flags);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertVol(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName nodeName,
        ResourceName resName,
        VolumeNumber volNr,
        StorPoolName storPoolName,
        String blockDev,
        String metaDisk,
        VlmFlags... flags
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_VOLUMES);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, nodeName.value);
        stmt.setString(3, resName.value);
        stmt.setInt(4, volNr.value);
        stmt.setString(5, storPoolName.value);
        stmt.setString(6, blockDev);
        stmt.setString(7, metaDisk);
        stmt.setLong(8, StateFlagsBits.getMask(flags));
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertStorPoolDfn(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        StorPoolName poolName
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_STOR_POOL_DEFINITIONS);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, poolName.value);
        stmt.setString(3, poolName.displayValue);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertStorPool(
        TransactionMgr transMgr,
        java.util.UUID uuid,
        NodeName nodeName,
        StorPoolName poolName,
        String driver
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_NODE_STOR_POOL);
        stmt.setBytes(1, UuidUtils.asByteArray(uuid));
        stmt.setString(2, nodeName.value);
        stmt.setString(3, poolName.value);
        stmt.setString(4, driver);
        stmt.executeUpdate();
        stmt.close();
    }

    protected static void insertProp(
        TransactionMgr transMgr,
        String instance,
        String key,
        String value
    )
        throws SQLException
    {
        PreparedStatement stmt = transMgr.dbCon.prepareStatement(INSERT_PROPS_CONTAINERS);
        stmt.setString(1, instance.toUpperCase());
        stmt.setString(2, key);
        stmt.setString(3, value);
        stmt.executeUpdate();
        stmt.close();
    }


}