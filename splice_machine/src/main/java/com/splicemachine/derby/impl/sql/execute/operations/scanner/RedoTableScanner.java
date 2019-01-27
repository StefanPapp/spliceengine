/*
 * Copyright (c) 2012 - 2017 Splice Machine, Inc.
 *
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package com.splicemachine.derby.impl.sql.execute.operations.scanner;

import com.splicemachine.access.impl.data.UnsafeRecord;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.io.FormatableBitSet;
import com.splicemachine.db.iapi.services.io.StoredFormatIds;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.iapi.types.HBaseRowLocation;
import com.splicemachine.db.iapi.types.RowLocation;
import com.splicemachine.derby.utils.StandardIterator;
import com.splicemachine.metrics.Counter;
import com.splicemachine.metrics.MetricFactory;
import com.splicemachine.metrics.Metrics;
import com.splicemachine.metrics.TimeView;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.api.server.TransactionalRegion;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.storage.*;
import com.splicemachine.utils.ByteSlice;
import org.apache.log4j.Logger;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder;
import org.apache.spark.sql.catalyst.encoders.RowEncoder;

import java.io.IOException;
import java.util.List;

/**
 * TableScanner which applies SI to generate a row
 * @author Scott Fines
 * Date: 4/4/14
 */
public class
RedoTableScanner<Data> implements TableScanner {
    public static ThreadLocal<String> regionId = new ThreadLocal<String>(){
        @Override
        protected String initialValue(){
            return "--";
        }
    };
    private static Logger LOG = Logger.getLogger(RedoTableScanner.class);
    private final Counter filterCounter;
    private DataScanner regionScanner;
    private final TransactionalRegion region;
    private final DataScan scan;
    protected final ExecRow template;
    private final boolean reuseRowLocation;
    private final String tableVersion;
    protected RowLocation currentRowLocation;
    private String indexName;
    private ByteSlice slice = new ByteSlice();
    private boolean isKeyed = true;
    private final Counter outputBytesCounter;
    private long demarcationPoint;
    private DataValueDescriptor optionalProbeValue;
    protected UnsafeRecord unsafeRecord;
    protected FormatableBitSet accessedColumns;
    protected int[] indexColsToMainColMap;
    protected final TxnView txn;
    private ExecRow defaultRow;
    private FormatableBitSet defaultValueMap;
    private int[] accessedColumnsIntArray;


    protected RedoTableScanner(FormatableBitSet accessedColumns,
                               int[] indexColsToMainColMap,
                               DataScanner scanner,
                               final TransactionalRegion region,
                               final ExecRow template,
                               DataScan scan,
                               final TxnView txn,
                               boolean reuseRowLocation,
                               String indexName,
                               final String tableVersion,
                               ExecRow defaultRow,
                               FormatableBitSet defaultValueMap) {
        assert template!=null:"Template cannot be null into a scanner";
        this.accessedColumns = accessedColumns;
        this.region = region;
        regionId.set(region.getRegionName());
        this.scan = scan;
        this.template = template;
        this.indexName = indexName;
        this.txn = txn;
        this.reuseRowLocation = reuseRowLocation;
        MetricFactory metricFactory = Metrics.noOpMetricFactory();
        this.filterCounter = metricFactory.newCounter();
        this.outputBytesCounter = metricFactory.newCounter();
        this.regionScanner = scanner;
        this.tableVersion = tableVersion;
        this.indexColsToMainColMap = indexColsToMainColMap;
        assert !(accessedColumns == null && indexColsToMainColMap ==null):"All nulls";
        if (accessedColumns != null)
            accessedColumnsIntArray = accessedColumns.getIntArray();
        unsafeRecord = new UnsafeRecord();
        this.defaultRow = defaultRow;
        this.defaultValueMap = defaultValueMap;

    }

    protected RedoTableScanner(FormatableBitSet accessedColumns,
                               int[] indexColsToMainColMap,
                               DataScanner scanner,
                               final TransactionalRegion region,
                               final ExecRow template,
                               DataScan scan,
                               final TxnView txn,
                               boolean reuseRowLocation,
                               String indexName,
                               final String tableVersion,
                               final long demarcationPoint,
                               ExecRow defaultRow,
                               FormatableBitSet defaultValueMap) {
        this(accessedColumns, indexColsToMainColMap, scanner, region, template, scan, txn, reuseRowLocation, indexName,
                tableVersion,defaultRow, defaultValueMap );
        this.demarcationPoint = demarcationPoint;
    }

    protected RedoTableScanner(FormatableBitSet accessedColumns,
                               int[] indexColsToMainColMap,
                               DataScanner scanner,
                               final TransactionalRegion region,
                               final ExecRow template,
                               DataScan scan,
                               final TxnView txn,
                               boolean reuseRowLocation,
                               String indexName,
                               final String tableVersion,
                               final long demarcationPoint,
                               DataValueDescriptor optionalProbeValue,
                               ExecRow defaultRow,
                               FormatableBitSet defaultValueMap) {
        this(accessedColumns, indexColsToMainColMap, scanner, region, template, scan, txn, reuseRowLocation, indexName,
                tableVersion, demarcationPoint, defaultRow, defaultValueMap);
        this.optionalProbeValue = optionalProbeValue;
    }

    @Override
    public void open() throws StandardException, IOException {
    }

    @Override
    public ExecRow next() throws StandardException, IOException {
        template.resetRowArray();
        //necessary to deal with null entries--maybe make the underlying call faster?
        do {
            List<DataCell> keyValues = regionScanner.next(-1);
            if (keyValues.size() <= 0) {
                currentRowLocation = null;
                return null;
            } else {
                DataCell currentKeyValue = keyValues.get(0);
                unsafeRecord.wrap(currentKeyValue);
                if (unsafeRecord.hasTombstone()) // Need Resolution mechanism...
                    continue;
                measureOutputSize(keyValues);
                if (accessedColumns!=null) {
                    unsafeRecord.getData(accessedColumnsIntArray, template);
                } else {
                    unsafeRecord.getData(indexColsToMainColMap, template);
                }
                //fill the unpopulated non-null columns with default values
                if (defaultRow != null && defaultValueMap != null) {
                    for (int i=defaultValueMap.anySetBit(); i>=0; i=defaultValueMap.anySetBit(i)) {
                        if (template.getColumn(i+1).isNull())
                            template.setColumn(i+1, defaultRow.getColumn(i+1).cloneValue(false));
                    }
                }
                setRowLocation(currentKeyValue);
                return template;
            }
        } while(true);
    }

    public long getBytesOutput(){
        return outputBytesCounter.getTotal();
    }

    private void measureOutputSize(List<DataCell> keyValues){
        if(outputBytesCounter.isActive()){
            for(DataCell cell:keyValues){
                outputBytesCounter.add(cell.encodedLength());
            }
        }

    }

    public RowLocation getCurrentRowLocation(){
        return currentRowLocation;
    }


    @Override
    public void close() throws StandardException, IOException {
        if (regionScanner != null)
            regionScanner.close();
    }

    public TimeView getTime(){
        return regionScanner.getReadTime();
    }

    public long getRowsFiltered(){
        return filterCounter.getTotal();
    }

    public long getRowsVisited() {
        return regionScanner.getRowsVisited();
    }

    public void setRegionScanner(DataScanner scanner){
        this.regionScanner = scanner;
    }

    public long getBytesVisited() {
        return regionScanner.getBytesOutput();
    }

    public DataScanner getRegionScanner() {
        return regionScanner;
    }


    protected void setRowLocation(DataCell sampleKv) throws StandardException {
        if(indexName!=null && template.nColumns() > 0 && template.getColumn(template.nColumns()).getTypeFormatId() == StoredFormatIds.ACCESS_HEAP_ROW_LOCATION_V1_ID) {
            currentRowLocation = (RowLocation) template.getColumn(template.nColumns());
        } else {
            if (reuseRowLocation) {
                slice.set(sampleKv.keyArray(), sampleKv.keyOffset(), sampleKv.keyLength());
            } else {
                slice = ByteSlice.wrap(sampleKv.keyArray(), sampleKv.keyOffset(), sampleKv.keyLength());
            }
            if (currentRowLocation == null || !reuseRowLocation)
                currentRowLocation = new HBaseRowLocation(slice);
            else
                currentRowLocation.setValue(slice);
        }
        template.setKey(currentRowLocation.getBytes()); // Ugh TODO JL
    }

}
