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

package com.splicemachine.storage;

import org.apache.hadoop.hbase.regionserver.ScannerContext;
import org.spark_project.guava.base.Function;
import org.spark_project.guava.collect.Iterators;
import org.spark_project.guava.collect.Lists;
import com.splicemachine.metrics.Counter;
import com.splicemachine.metrics.MetricFactory;
import com.splicemachine.metrics.TimeView;
import com.splicemachine.metrics.Timer;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Scott Fines
 *         Date: 12/14/15
 */
public class RegionDataScanner implements DataScanner{
    protected final Timer readTimer;
    protected final Counter outputBytesCounter;
    protected final Counter filteredRowCounter;
    protected final Counter visitedRowCounter;
    protected final RegionScanner delegate;
    protected final Partition partition;

    protected List<Cell> internalList;

    protected HCell cell = new HCell();

    private final Function<Cell,DataCell> transform = new Function<Cell, DataCell>(){
        @Override
        public DataCell apply(Cell input){
               return new HCell(input);
        }
    };

    public RegionDataScanner(Partition source,RegionScanner delegate,MetricFactory metricFactory){
        this.delegate=delegate;
        this.readTimer = metricFactory.newTimer();
        this.partition = source;
        this.outputBytesCounter = metricFactory.newCounter();
        this.filteredRowCounter = metricFactory.newCounter();
        this.visitedRowCounter = metricFactory.newCounter();
    }

    @Override
    public Partition getPartition(){
        return partition;
    }

    @Override
    public @Nonnull List<DataCell> next(int limit) throws IOException{
        if(internalList==null)
            internalList = new ArrayList<>(limit>0?limit:10);
        internalList.clear();
        readTimer.startTiming();
        delegate.nextRaw(internalList);
        if(!internalList.isEmpty()){
            readTimer.tick(1);
            collectMetrics(internalList);
        }else
            readTimer.stopTiming();
        return Lists.transform(internalList,transform);
    }

    @Override public TimeView getReadTime(){ return readTimer.getTime(); }
    @Override public long getBytesOutput(){ return outputBytesCounter.getTotal(); }
    @Override public long getRowsFiltered(){ return filteredRowCounter.getTotal(); }
    @Override public long getRowsVisited(){ return readTimer.getNumEvents(); }

    @Override public void close() throws IOException{ delegate.close(); }


    /* *********************************************************************************************************/
    /*private helper methods*/
    private void collectMetrics(List<Cell> internalList){
        if(outputBytesCounter.isActive()){
            for(int i=0;i<internalList.size();i++){
                Cell c = internalList.get(i);
                outputBytesCounter.add(c.getRowLength()+c.getQualifierLength()+c.getFamilyLength()+c.getValueLength());
            }
        }
    }

    @Override
    public boolean reseek(byte[] rowKey) throws IOException {
        return delegate.reseek(rowKey);
    }

}
