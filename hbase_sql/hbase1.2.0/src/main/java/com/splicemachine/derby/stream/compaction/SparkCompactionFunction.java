/*
 * Copyright (c) 2012 - 2019 Splice Machine, Inc.
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
package com.splicemachine.derby.stream.compaction;

import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.compactions.SpliceDefaultCompactor;
import com.splicemachine.hbase.ReadOnlyHTableDescriptor;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.storage.ClientPartition;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.commons.collections.iterators.EmptyListIterator;
import org.apache.commons.collections.iterators.SingletonIterator;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.regionserver.*;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.log4j.Logger;
import scala.Tuple2;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Created by jyuan on 4/12/19.
 */
public class SparkCompactionFunction extends  BaseSparkCompactionFunction {

    private static final Logger LOG = Logger.getLogger(SparkCompactionFunction.class);

    public SparkCompactionFunction(){}

    public SparkCompactionFunction(long smallestReadPoint, byte[] namespace, byte[] tableName, HRegionInfo hri,
                                   byte[] storeColumn, boolean isMajor, InetSocketAddress[] favoredNodes) {
        super(smallestReadPoint, namespace, tableName, hri, storeColumn, isMajor, favoredNodes);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Iterator<String> call(Iterator it) throws Exception {

        ArrayList<StoreFile> readersToClose = new ArrayList<StoreFile>();
        Configuration conf = HConfiguration.unwrapDelegate();
        TableName tn = TableName.valueOf(namespace, tableName);
        PartitionFactory tableFactory=SIDriver.driver().getTableFactory();
        Table table = (((ClientPartition)tableFactory.getTable(tn)).unwrapDelegate());

        FileSystem fs = FSUtils.getCurrentFileSystem(conf);
        Path rootDir = FSUtils.getRootDir(conf);

        HTableDescriptor htd = table.getTableDescriptor();
        HRegion region = HRegion.openHRegion(conf, fs, rootDir, hri, new ReadOnlyHTableDescriptor(htd), null, null, null);
        Store store = region.getStore(storeColumn);

        assert it.hasNext();
        Tuple2 t = (Tuple2)it.next();
        Iterator files = (Iterator)t._2;
        if (LOG.isTraceEnabled()) {
            LOG.trace("compacting files: ");
        }
        while (files.hasNext()) {
            String file = (String)files.next();
            if (LOG.isTraceEnabled()) {
                LOG.trace(file + "\n");
            }
            readersToClose.add(
                    new StoreFile(
                            fs,
                            new Path(file),
                            conf,
                            store.getCacheConfig(),
                            store.getFamily().getBloomFilterType()
                    )
            );
        }

        SpliceDefaultCompactor sdc = new SpliceDefaultCompactor(conf, store, smallestReadPoint);
        CompactionRequest compactionRequest = new CompactionRequest(readersToClose);
        compactionRequest.setIsMajor(isMajor, isMajor);
        List<Path> paths = sdc.sparkCompact(compactionRequest, context, favoredNodes);

        if (LOG.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder(100);
            sb.append(String.format("Result %d paths: ", paths.size()));
            for (Path path: paths) {
                sb.append(String.format("\nPath: %s", path));
            }
            SpliceLogUtils.trace(LOG, sb.toString());
        }
        return (paths == null || paths.isEmpty()) ?
                EmptyListIterator.INSTANCE:
                new SingletonIterator(paths.get(0).toString());
    }


}