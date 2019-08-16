package com.splicemachine.hbase;

import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.configuration.*;
import com.splicemachine.access.configuration.HBaseConfiguration;
import com.splicemachine.access.hbase.HBaseConnectionFactory;
import com.splicemachine.concurrent.SystemClock;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.si.data.hbase.coprocessor.HBaseSIEnvironment;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.timestamp.api.TimestampSource;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by jyuan on 8/1/19.
 */
public class ReplicationProgressTrackerChore extends ScheduledChore {

    private static final Logger LOG = Logger.getLogger(ReplicationProgressTrackerChore.class);
    Connection connection;
    private Map<String, Long> replicationProgress = new HashMap<String, Long>();
    private TableName masterSnapshotTable;
    private TableName replicationProgressTable;
    private long latestTimestamp;

    public ReplicationProgressTrackerChore(final String name, final Stoppable stopper, final int period) throws IOException {
        super(name, stopper, period);
        init();
    }

    private void init() throws IOException {
        connection = HBaseConnectionFactory.getInstance(HConfiguration.getConfiguration()).getConnection();
        String namespace = HConfiguration.getConfiguration().getNamespace();
        masterSnapshotTable = TableName.valueOf(namespace, HBaseConfiguration.MASTER_SNAPSHOTS_TABLE_NAME);
        replicationProgressTable = TableName.valueOf(namespace, HBaseConfiguration.SLAVE_REPLICATION_PROGRESS_TABLE_NAME);

    }

    @Override
    protected void chore() {
        try {
            Admin admin = connection.getAdmin();
            if (!admin.tableExists(replicationProgressTable))
                return;

            if (latestTimestamp != 0 && noMoreProgress(connection)) {
                // Do nothing if no progress was made
                return;
            }

            if (replicationProgress.size() == 0) {
                getReplicationProgress(connection, replicationProgress);
                // If there is no entry in SLAVE_REPLICATION_PROGRESS, do nothing
                if (replicationProgress.size() == 0)
                    return;
            }
            updateProgress();
        } catch (TableNotFoundException e) {
            // Ignore TableNotFoundException during database creation
            SpliceLogUtils.warn(LOG, "Unexpected exception", e);
        } catch (Exception e) {
            SpliceLogUtils.error(LOG, "Unexpected exception", e);
            throw new RuntimeException(e);
        }
    }


    private boolean noMoreProgress(Connection conn) throws IOException{

        Table progressTable = conn.getTable(replicationProgressTable);
        Get getReplicationProgress = new Get(HBaseConfiguration.REPLICATION_PROGRESS_ROWKEY_BYTES);
        Result r = progressTable.get(getReplicationProgress);
        Cell cell = r.getColumnLatestCell(SIConstants.DEFAULT_FAMILY_BYTES, HBaseConfiguration.REPLICATION_PROGRESS_TSCOL_BYTES);
        long timestamp = Bytes.toLong(CellUtil.cloneValue(cell));
        boolean noProgress = (timestamp == latestTimestamp);
        latestTimestamp = timestamp;
        return noProgress;
    }

    /**
     * Get current replication progress
     * @param conn
     * @param replicationProgress
     * @throws IOException
     */
    private void getReplicationProgress(Connection conn, Map<String, Long> replicationProgress) throws IOException {
        Table progressTable = conn.getTable(replicationProgressTable);
        Get getReplicationProgress = new Get(HBaseConfiguration.REPLICATION_PROGRESS_ROWKEY_BYTES);
        Result r = progressTable.get(getReplicationProgress);

        CellScanner scanner = r.cellScanner();
        while (scanner.advance()) {
            Cell cell = scanner.current();
            byte[] colName = CellUtil.cloneQualifier(cell);
            if(Arrays.equals(colName, HBaseConfiguration.REPLICATION_PROGRESS_TSCOL_BYTES)){
                latestTimestamp = Bytes.toLong(CellUtil.cloneValue(cell));
                //if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.info(LOG, "timestamp = %d", latestTimestamp);
                //}
            }
            else {
                String region = Bytes.toString(CellUtil.cloneQualifier(cell));
                Long seqNum = Bytes.toLong(CellUtil.cloneValue(cell));
                replicationProgress.put(region, seqNum);
                //if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.info(LOG, "region=%s, seqNum=%s", region, seqNum);
                //}
            }
        }
    }

    /**
     * Try to update replication progress
     * @throws IOException
     * @throws KeeperException
     * @throws InterruptedException
     */
    private void updateProgress() throws IOException, KeeperException, InterruptedException {
        Table snapshotTable = connection.getTable(masterSnapshotTable);
        Scan scan = new Scan();
        try (ResultScanner scanner = snapshotTable.getScanner(scan)) {
            for (Result r : scanner) {
                byte[] rowKey = r.getRow();
                long timestamp = new Long(new String(rowKey));
                //if (LOG.isDebugEnabled()) {
                    SpliceLogUtils.info(LOG, "Checking snapshot taken at %d", timestamp);
                //}
                CellScanner s = r.cellScanner();
                while (s.advance()) {
                    Cell cell = s.current();
                    String region = Bytes.toString(CellUtil.cloneQualifier(cell));
                    Long seqNum = Bytes.toLong(CellUtil.cloneValue(cell));
                    if (replicationProgress.containsKey(region)) {
                        long appliedSeqNum = replicationProgress.get(region);
                        //if (LOG.isDebugEnabled()) {
                            SpliceLogUtils.info(LOG,
                                    "region=%s, snapshot=%d, progress=%d", region, seqNum, appliedSeqNum);
                        //}
                        if (appliedSeqNum < seqNum) {
                            // applied seqNum is behind snapshot seqNum,cannot move timestamp forward
                            return;
                        }
                    }
//                    else {
//                        // If no replication has completed for this region, cannot move timestamp forward
//                        return;
//                    }
                }
                Delete d = new Delete(rowKey);
                // We have replicated beyond this snapshot, delete it and bump up timestamp
                snapshotTable.delete(d);
                setTimestamp(timestamp);
            }
        }finally {
            replicationProgress.clear();
        }
    }

    private void setTimestamp(long timestamp) throws IOException, KeeperException, InterruptedException {
        TimestampSource timestampSource = SIDriver.driver().getTimestampSource();
        long currentTimestamp = timestampSource.nextTimestamp();
        if (currentTimestamp < timestamp) {
            RecoverableZooKeeper rzk = ZkUtils.getRecoverableZooKeeper();
            HBaseSIEnvironment env = HBaseSIEnvironment.loadEnvironment(new SystemClock(), rzk);
            ConfigurationSource configurationSource = env.configuration().getConfigSource();
            String rootNode = configurationSource.getString(HConfiguration.SPLICE_ROOT_PATH, HConfiguration.DEFAULT_ROOT_PATH);
            String node = rootNode + HConfiguration.MAX_RESERVED_TIMESTAMP_PATH;
            //if (LOG.isDebugEnabled()) {
                SpliceLogUtils.info(LOG, "bump up timestamp to %d", timestamp);
            //}
            byte[] data = Bytes.toBytes(timestamp);
            rzk.setData(node, data, -1 /* version */);
            timestampSource.refresh();
        }
        else {
            //if (LOG.isDebugEnabled()) {
                SpliceLogUtils.info(LOG, "current timestamp = %d. Applied changes from master until timestamp %d",
                        currentTimestamp, timestamp);
            //}
        }
    }
}