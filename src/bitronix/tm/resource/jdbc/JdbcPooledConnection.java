package bitronix.tm.resource.jdbc;

import bitronix.tm.BitronixXid;
import bitronix.tm.internal.BitronixSystemException;
import bitronix.tm.internal.Decoder;
import bitronix.tm.internal.ManagementRegistrar;
import bitronix.tm.internal.XAResourceHolderState;
import bitronix.tm.resource.common.*;
import bitronix.tm.resource.jdbc.lrc.LrcXADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Implementation of a JDBC pooled connection wrapping vendor's {@link XAConnection} implementation.
 * <p>&copy; Bitronix 2005, 2006, 2007</p>
 *
 * @author lorban
 */
public class JdbcPooledConnection extends AbstractXAResourceHolder implements StateChangeListener, JdbcPooledConnectionMBean {

    private final static Logger log = LoggerFactory.getLogger(JdbcPooledConnection.class);

    private XAConnection xaConnection;
    private Connection connection;
    private XAResource xaResource;
    private PoolingDataSource poolingDataSource;
    private boolean emulateXa = false;

    /* management */
    private String jmxName;
    private Date acquisitionDate;
    private Date lastReleaseDate;


    public JdbcPooledConnection(PoolingDataSource poolingDataSource, XAConnection xaConnection) throws SQLException {
        this.poolingDataSource = poolingDataSource;
        this.xaConnection = xaConnection;
        this.xaResource = xaConnection.getXAResource();
        addStateChangeEventListener(this);

        if (poolingDataSource.getClassName().equals(LrcXADataSource.class.getName())) {
            if (log.isDebugEnabled()) log.debug("emulating XA for resource " + poolingDataSource.getUniqueName());
            emulateXa = true;
        }

        this.jmxName = "bitronix.tm:type=JdbcPooledConnection,UniqueName=" + poolingDataSource.getUniqueName() + ",Id=" + poolingDataSource.incCreatedResourcesCounter();
        ManagementRegistrar.register(jmxName, this);
    }

    public void close() throws SQLException {
        setState(STATE_CLOSED);
        xaConnection.close();
    }

    public RecoveryXAResourceHolder createRecoveryXAResourceHolder() {
        return new RecoveryXAResourceHolder(this);
    }

    private void testConnection(Connection connection) throws SQLException {
        String query = poolingDataSource.getTestQuery();
        if (query == null) {
            if (log.isDebugEnabled()) log.debug("no query to test connection of " + this + ", skipping test");
            return;
        }

        if (log.isDebugEnabled()) log.debug("testing with query '" + query + "' connection of " + this);
        PreparedStatement stmt = connection.prepareStatement(query);
        ResultSet rs = stmt.executeQuery();
        rs.close();
        stmt.close();
        if (log.isDebugEnabled()) log.debug("successfully tested connection of " + this);
    }

    protected void release() throws SQLException {
        if (log.isDebugEnabled()) log.debug("releasing to pool " + this);

        // delisting
        try {
            TransactionContextHelper.delistFromCurrentTransaction(this, poolingDataSource);
        } catch (SystemException ex) {
            throw (SQLException) new SQLException("error delisting " + this).initCause(ex);
        }

        // requeuing
        try {
            TransactionContextHelper.requeue(this, poolingDataSource);
        } catch (BitronixSystemException ex) {
            throw (SQLException) new SQLException("error requeueing " + this).initCause(ex);
        }

        if (log.isDebugEnabled()) log.debug("released to pool " + this);
    }

    public XAResource getXAResource() {
        return xaResource;
    }

    public boolean isEmulatingXA() {
        return emulateXa;
    }

    /**
     * If this method returns false, then local transaction calls like Connection.commit() can be made.
     * @return true if start() has been successfully called but not end() yet <i>and</i> the transaction is not suspended.
     */
    public boolean isParticipatingInActiveGlobalTransaction() {
        XAResourceHolderState xaResourceHolderState = getXAResourceHolderState();
        if (xaResourceHolderState == null)
            return false;
        return (xaResourceHolderState.isStarted()) && (!xaResourceHolderState.isSuspended()) && (!xaResourceHolderState.isEnded());
    }

    public PoolingDataSource getPoolingDataSource() {
        return poolingDataSource;
    }

    public List getXAResourceHolders() {
        List xaResourceHolders = new ArrayList();
        xaResourceHolders.add(this);
        return xaResourceHolders;
    }

    public Object getConnectionHandle() throws Exception {
        if (log.isDebugEnabled()) log.debug("getting connection handle from " + this);
        int oldState = getState();
        setState(STATE_ACCESSIBLE);
        connection = xaConnection.getConnection();
        if (oldState == STATE_IN_POOL) {
            if (log.isDebugEnabled()) log.debug("connection " + xaConnection + " was in state STATE_IN_POOL, testing it");
            testConnection(connection);
        }
        else {
            if (log.isDebugEnabled()) log.debug("connection " + xaConnection + " was in state " + Decoder.decodeXAStatefulHolderState(oldState) + ", no need to test it");
        }
        if (log.isDebugEnabled()) log.debug("got connection handle from " + this);
        return new JdbcConnectionHandle(this, connection);
    }

    public void stateChanged(XAStatefulHolder source, int oldState, int newState) {
        if (newState == STATE_IN_POOL) {
            if (log.isDebugEnabled()) log.debug("requeued JDBC connection of " + poolingDataSource);
            lastReleaseDate = new Date();
        }
        if (oldState == STATE_IN_POOL && newState == STATE_ACCESSIBLE) {
            acquisitionDate = new Date();
        }
        if (oldState == STATE_NOT_ACCESSIBLE && newState == STATE_ACCESSIBLE) {
            TransactionContextHelper.markRecycled(this);
        }
        if (newState == STATE_CLOSED) {
            ManagementRegistrar.unregister(jmxName);
        }
    }

    public void stateChanging(XAStatefulHolder source, int currentState, int futureState) {
        if (futureState == STATE_IN_POOL) {
            try {
                if (!connection.getAutoCommit()) {
                    if (log.isDebugEnabled()) log.debug("resetting autocommit mode of " + connection);
                    connection.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                log.warn("error resetting autocommit mode", ex);
            }

            if (poolingDataSource.getKeepConnectionOpenUntilAfter2Pc()) {
                try {
                    if (log.isDebugEnabled()) log.debug("2PC is done, closing connection: " + connection);
                    connection.close();
                } catch (SQLException ex) {
                    log.warn("error closing connection", ex);
                }
                connection = null;
            }
        }
    }

    public String toString() {
        return "a JdbcPooledConnection from datasource " + poolingDataSource.getUniqueName() + " in state " + Decoder.decodeXAStatefulHolderState(getState()) + " wrapping " + xaConnection;
    }

    /* management */

    public String getStateDescription() {
        return Decoder.decodeXAStatefulHolderState(getState());
    }

    public Date getAcquisitionDate() {
        return acquisitionDate;
    }

    public Date getLastReleaseDate() {
        return lastReleaseDate;
    }

    public String getTransactionGtridCurrentlyHoldingThis() {
        return ((BitronixXid) getXAResourceHolderState().getXid()).getGlobalTransactionIdUid().toString();
    }

}