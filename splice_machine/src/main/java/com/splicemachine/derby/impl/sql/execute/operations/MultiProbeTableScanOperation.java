
package com.splicemachine.derby.impl.sql.execute.operations;

import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.io.ArrayUtil;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.store.access.StaticCompiledOpenConglomInfo;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.compile.RowOrdering;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.derby.stream.iapi.DataSet;
import com.splicemachine.derby.stream.iapi.DataSetProcessor;
import com.splicemachine.derby.stream.output.WriteReadUtils;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.storage.DataScan;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Result set that fetches rows from a scan by "probing" the underlying
 * table with a given list of values.  Repeated calls to nextRow()
 * will first return all rows matching probeValues[0], then all rows matching
 * probeValues[1], and so on (duplicate probe values are ignored).  Once all
 * matching rows for all values in probeValues have been returned, the call
 * to nextRow() will return null, thereby ending the scan. The
 * expectation is that this kind of result set only ever appears beneath
 * some other top-level result set (esp. IndexRowToBaseRowResultSet), in
 * which case all result sets higher up in the result set tree will just
 * see a stream of rows satisfying the list of probe values.
 *
 * Currently this type of result is used for evaluation of IN lists, where
 * the user wants to retrieve all rows for which some target column has a
 * value that equals one of values in the IN list.  In that case the IN list
 * values are represented by the probeValues array.
 *
 * Most of the work for this class is inherited from TableScanResultSet. 
 * This class overrides four public methods and two protected methods
 * from TableScanResultSet.  In all cases the methods here set probing
 * state and then call the corresponding methods on "super".
 */
public class MultiProbeTableScanOperation extends TableScanOperation  {
    private static final long serialVersionUID = 1l;
    /** The values with which we will probe the table. */
    protected DataValueDescriptor [] probeValues;
//    /**
//     * The values with which we will probe the table, as they were passed to
//     * the constructor. We need to keep them unchanged in case the result set
//     * is reused when a statement is re-executed (see DERBY-827).
//     */
//    protected DataValueDescriptor [] origProbeValues;

//    /**
//     * 0-based position of the <b>next</b> value to lookup w.r.t. the probe
//     * values list.
//     */
//    protected int probeValIndex;

    /**
     * Indicator as to which type of sort we need: ASCENDING, DESCENDING,
     * or NONE (NONE is represented by "RowOrdering.DONTCARE" and is used
     * for cases where all necessary sorting occurred at compilation time).
     */
    private int sortRequired;

//    /**
//     * Tells whether or not we should skip the next attempt to (re)open the
//     * scan controller. If it is {@code true} it means that the previous call
//     * to {@link #initStartAndStopKey()} did not find a new probe value, which
//     * means that the probe list is exhausted and we shouldn't perform a scan.
//     */
//    private boolean skipNextScan;

    /**
     * Only used for Serialization, DO NOT USE
     */
    @Deprecated
    public MultiProbeTableScanOperation() { }

    /**
     * Constructor.  Just save off the relevant probing state and pass
     * everything else up to TableScanResultSet.
     * 
     * @exception StandardException thrown on failure to open
     */
    public MultiProbeTableScanOperation(long conglomId,
        StaticCompiledOpenConglomInfo scoci, Activation activation, 
        GeneratedMethod resultRowAllocator, 
        int resultSetNumber,
        GeneratedMethod startKeyGetter, int startSearchOperator,
        GeneratedMethod stopKeyGetter, int stopSearchOperator,
        boolean sameStartStopPosition,
        boolean rowIdKey,
        String qualifiersField,
        DataValueDescriptor [] probingVals,
        int sortRequired,
        String tableName,
        String userSuppliedOptimizerOverrides,
        String indexName,
        boolean isConstraint,
        boolean forUpdate,
        int colRefItem,
        int indexColItem,
        int lockMode,
        boolean tableLocked,
        int isolationLevel,
        boolean oneRowScan,
        double optimizerEstimatedRowCount,
        double optimizerEstimatedCost, String tableVersion)
            throws StandardException
    {
        /* Note: We use '1' as rows per read because we do not currently
         * allow bulk fetching when multi-probing.  If that changes in
         * the future then we will need to update rowsPerRead accordingly.
         */
        super(conglomId,
            scoci,
            activation,
            resultRowAllocator,
            resultSetNumber,
            startKeyGetter,
            startSearchOperator,
            stopKeyGetter,
            stopSearchOperator,
            sameStartStopPosition,
            rowIdKey,
            qualifiersField,
            tableName,
            userSuppliedOptimizerOverrides,
            indexName,
            isConstraint,
            forUpdate,
            colRefItem,
            indexColItem,
            lockMode,
            tableLocked,
            isolationLevel,
            1, // rowsPerRead
            oneRowScan,
            optimizerEstimatedRowCount,
            optimizerEstimatedCost,tableVersion);

        if (SanityManager.DEBUG)
        {
            SanityManager.ASSERT(
                    (probingVals != null) && (probingVals.length > 0),
                    "No probe values found for multi-probe scan.");
        }
        this.sortRequired = sortRequired;


        if (sortRequired == RowOrdering.DONTCARE) // Already Sorted
            probeValues = probingVals;
        else {
            /* RESOLVE: For some reason sorting the probeValues array
             * directly leads to incorrect parameter value assignment when
             * executing a prepared statement multiple times.  Need to figure
             * out why (maybe related to DERBY-827?).  In the meantime, if
             * we're going to sort the values we use clones.  This is not
             * ideal, but it works for now.
             */
            DataValueDescriptor[] probeValues =
                    new DataValueDescriptor[probingVals.length];

            for (int i = 0; i < probeValues.length; i++)
                probeValues[i] = probingVals[i].cloneValue(false);

            if (sortRequired == RowOrdering.ASCENDING)
                Arrays.sort(probeValues);
            else
                Arrays.sort(probeValues, Collections.reverseOrder());
        }
        this.scanInformation = new MultiProbeDerbyScanInformation(
                resultRowAllocator.getMethodName(),
                startKeyGetter==null?null:startKeyGetter.getMethodName(),
                stopKeyGetter==null?null:stopKeyGetter.getMethodName(),
                qualifiersField==null?null:qualifiersField,
                conglomId,
                colRefItem,
                sameStartStopPosition,
                startSearchOperator,
                stopSearchOperator,
                probingVals,
                tableVersion
        );
        init();
    }


    @Override
    public void init(SpliceOperationContext context) throws StandardException, IOException {
        super.init(context);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        probeValues = new DataValueDescriptor[in.readInt()];
        ArrayUtil.readArrayItems(in,probeValues);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        ArrayUtil.writeArray(out,probeValues);
    }

    @Override
    public String toString() {
        return "MultiProbe"+super.toString();
    }

    @Override
    public DataSet<LocatedRow> getDataSet(DataSetProcessor dsp) throws StandardException {
        TxnView txn = getCurrentTransaction();
        List<DataScan> scans = scanInformation.getScans(getCurrentTransaction(), null, activation, getKeyDecodingMap());
        DataSet<LocatedRow> dataSet = dsp.getEmpty();
        for (DataScan scan: scans) {
            deSiify(scan);
            DataSet<LocatedRow> ds = dsp.<MultiProbeTableScanOperation,LocatedRow>newScanSet(this,tableName)
                    .tableDisplayName(tableDisplayName)
                    .activation(activation)
                    .transaction(txn)
                    .scan(scan)
                    .template(currentTemplate)
                    .tableVersion(tableVersion)
                    .indexName(indexName)
                    .reuseRowLocation(false)
                    .keyColumnEncodingOrder(scanInformation.getColumnOrdering())
                    .keyColumnSortOrder(scanInformation.getConglomerate().getAscDescInfo())
                    .keyColumnTypes(getKeyFormatIds())
                    .execRowTypeFormatIds(WriteReadUtils.getExecRowTypeFormatIds(currentTemplate))
                    .accessedKeyColumns(scanInformation.getAccessedPkColumns())
                    .keyDecodingMap(getKeyDecodingMap())
                    .rowDecodingMap(baseColumnMap)
                    .buildDataSet(this);
            dataSet = dataSet.union(ds);
        }
        return dataSet;
    }
        
}
