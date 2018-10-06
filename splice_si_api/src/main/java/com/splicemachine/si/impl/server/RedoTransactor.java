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

package com.splicemachine.si.impl.server;

import com.carrotsearch.hppc.*;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.splicemachine.access.impl.data.UnsafeRecord;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.kvpair.KVPair;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.api.data.ExceptionFactory;
import com.splicemachine.si.api.data.OperationFactory;
import com.splicemachine.si.api.data.OperationStatusFactory;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.api.readresolve.RollForward;
import com.splicemachine.si.api.server.ConstraintChecker;
import com.splicemachine.si.api.server.Transactor;
import com.splicemachine.si.api.txn.*;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.si.impl.ConflictResults;
import com.splicemachine.si.impl.SpliceQuery;
import com.splicemachine.si.impl.functions.ScannerFunctionUtils;
import com.splicemachine.storage.*;
import com.splicemachine.utils.ByteSlice;
import com.splicemachine.utils.IntArrays;
import com.splicemachine.utils.Pair;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;
import java.io.IOException;
import java.util.*;
import java.util.BitSet;
import java.util.concurrent.locks.Lock;

import static com.splicemachine.si.constants.SIConstants.SI_NEEDED;
import static com.splicemachine.si.constants.SIConstants.SI_NEEDED_VALUE_BYTES;
import static com.splicemachine.si.constants.SIConstants.SI_TRANSACTION_ID_KEY;

/**
 * Central point of implementation of the "snapshot isolation" MVCC algorithm that provides transactions across atomic
 * row updates in the underlying store. This is the core brains of the SI logic.
 */
@SuppressWarnings("unchecked")
public class RedoTransactor implements Transactor{
    public static final ThreadLocal<SpliceQuery> queryContext = new ThreadLocal<SpliceQuery>();
    private static final Logger LOG=Logger.getLogger(RedoTransactor.class);
    private final OperationFactory opFactory;
    private final OperationStatusFactory operationStatusLib;
    private final ExceptionFactory exceptionLib;

    private final TxnOperationFactory txnOperationFactory;
    private final TxnSupplier txnSupplier;

    public RedoTransactor(TxnSupplier txnSupplier,
                          TxnOperationFactory txnOperationFactory,
                          OperationFactory opFactory,
                          OperationStatusFactory operationStatusLib,
                          ExceptionFactory exceptionFactory){
        this.txnSupplier=txnSupplier;
        this.txnOperationFactory=txnOperationFactory;
        this.opFactory= opFactory;
        this.operationStatusLib = operationStatusLib;
        this.exceptionLib = exceptionFactory;
    }

    // Operation pre-processing. These are to be called "server-side" when we are about to process an operation.

    // Process update operations

    @Override
    public boolean processPut(Partition table,RollForward rollForwardQueue,DataPut put) throws IOException{
        if(!isFlaggedForSITreatment(put)) return false;
        List<KVPair> kvPairs = new ArrayList<>(1);
        Iterator<DataCell> it = put.cells().iterator();
        if (it.hasNext()) {
            UnsafeRecord unsafeRecord = new UnsafeRecord(it.next());
            kvPairs.add(unsafeRecord.getKVPair());
        }
        if (put.getAttribute(SIConstants.SI_EXEC_ROW) ==null)
            throw new UnsupportedOperationException("processPut must have an execRow serialized on the put");
        ExecRow execRow = opFactory.getTemplate(put);
        if (put.getAttribute(SIConstants.SI_TRANSACTION_ID_KEY) ==null)
            throw new UnsupportedOperationException("processPut must have a txn serialized");
        byte[] attribute = put.getAttribute(SIConstants.SI_TRANSACTION_ID_KEY);
        TxnView txn=txnOperationFactory.fromReads(attribute,0,attribute.length);


        MutationStatus[] operationStatuses = processKvBatch(table,null,SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,SIConstants.PACKED_COLUMN_BYTES,
                kvPairs,txn,operationStatusLib.getNoOpConstraintChecker(), false, false,execRow);
        return operationStatusLib.processPutStatus(operationStatuses[0]);
    }

    @Override
    public MutationStatus[] processPutBatch(Partition table,RollForward rollForwardQueue,DataPut[] mutations) throws IOException{
        if(mutations.length==0){
            //short-circuit special case of empty batch
            //noinspection unchecked
            return new MutationStatus[0];
        }
        if (mutations[0].getAttribute(SIConstants.SI_EXEC_ROW) ==null)
            throw new UnsupportedOperationException("processPut must have an execRow serialized on the put");
        ExecRow execRow = opFactory.getTemplate(mutations[0]);

        if (mutations[0].getAttribute(SIConstants.SI_TRANSACTION_ID_KEY) ==null)
            throw new UnsupportedOperationException("processPut must have a txn serialized");
        byte[] attribute = mutations[0].getAttribute(SIConstants.SI_TRANSACTION_ID_KEY);
        TxnView txn=txnOperationFactory.fromReads(attribute,0,attribute.length);

        long txnId = -1;
        List<KVPair> kvPairs = new ArrayList<>(mutations.length);
        for (int i = 0; i< mutations.length; i++) {

            Iterator<DataCell> it = mutations[i].cells().iterator();
            if (it.hasNext()) {
                UnsafeRecord unsafeRecord = new UnsafeRecord(it.next());
                if (i==0)
                    txnId = unsafeRecord.getTxnId1();
                kvPairs.add(unsafeRecord.getKVPair());
            }
        }

        return processKvBatch(table,null,SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,
                SIConstants.PACKED_COLUMN_BYTES,kvPairs,txn,operationStatusLib.getNoOpConstraintChecker(),false, false, execRow);
    }

    @Override
    public MutationStatus[] processKvBatch(Partition table,
                                            RollForward rollForward,
                                            byte[] defaultFamilyBytes,
                                            byte[] packedColumnBytes,
                                            Collection<KVPair> toProcess,
                                            TxnView txn,
                                            ConstraintChecker constraintChecker,ExecRow execRow) throws IOException{
        return processKvBatch(table,rollForward,defaultFamilyBytes,packedColumnBytes,toProcess,txn,constraintChecker, false, false,execRow);
    }

    @Override
    public MutationStatus[] processKvBatch(Partition table,
                                           RollForward rollForward,
                                           byte[] defaultFamilyBytes,
                                           byte[] packedColumnBytes,
                                           Collection<KVPair> toProcess,
                                           TxnView txn,
                                           ConstraintChecker constraintChecker,
                                           boolean skipConflictDetection,
                                           boolean skipWAL,
                                           ExecRow execRow) throws IOException{
        assert execRow!=null:"Exec Row is null";
        ensureTransactionAllowsWrites(txn);
        return processInternal(table,rollForward,txn,defaultFamilyBytes,packedColumnBytes,toProcess,constraintChecker,skipConflictDetection,skipWAL,execRow);
    }

    private MutationStatus getCorrectStatus(MutationStatus status,MutationStatus oldStatus){
        return operationStatusLib.getCorrectStatus(status,oldStatus);
    }

    private MutationStatus[] processInternal(Partition table,
                                             RollForward rollForwardQueue,
                                             TxnView txn,
                                             byte[] family, byte[] qualifier,
                                             Collection<KVPair> mutations,
                                             ConstraintChecker constraintChecker,
                                             boolean skipConflictDetection,
                                             boolean skipWAL, ExecRow execRow) throws IOException{
//                if (LOG.isTraceEnabled()) LOG.trace(String.format("processInternal: table = %s, txnId = %s", table.toString(), txn.getTxnId()));
        assert execRow != null:"Exec Row is Null";
        MutationStatus[] finalStatus=new MutationStatus[mutations.size()];
        Pair<KVPair, Lock>[] lockPairs=new Pair[mutations.size()];
//        TxnFilter constraintState=null;
        /*
        if(constraintChecker!=null)
            constraintState=new SimpleActiveTxnFilter(txn,NoOpReadResolver.INSTANCE,txnSupplier);
         */
        @SuppressWarnings("unchecked") final LongOpenHashSet[] conflictingChildren=new LongOpenHashSet[mutations.size()];
        try{
            lockRows(table,mutations,lockPairs,finalStatus);

            /*
             * You don't need a low-level operation check here, because this code can only be called from
             * 1 of 2 paths (bulk write pipeline and SIObserver). Since both of those will externally ensure that
             * the region can't close until after this method is complete, we don't need the calls.
             */
            IntObjectOpenHashMap<DataPut> writes=checkConflictsForKvBatch(table,rollForwardQueue,lockPairs,
                    conflictingChildren,txn,family,qualifier,constraintChecker,finalStatus,skipConflictDetection,skipWAL,execRow);

            //TODO -sf- this can probably be made more efficient
            //convert into array for usefulness
            DataPut[] toWrite=new DataPut[writes.size()];
            int i=0;
            for(IntObjectCursor<DataPut> write : writes){
                toWrite[i]=write.value;
                i++;
            }
            Iterator<MutationStatus> status=table.writeBatch(toWrite);

            //convert the status back into the larger array
            i=0;
            for(IntObjectCursor<DataPut> write : writes){
                if(!status.hasNext())
                    throw new IllegalStateException("Programmer Error: incorrect length for returned status");
                finalStatus[write.key]=status.next().getClone(); //TODO -sf- is clone needed here?
                //resolve child conflicts
                try{
                    resolveChildConflicts(table,write.value,conflictingChildren[i]);
                }catch(Exception e){
                    finalStatus[i] = operationStatusLib.failure(e);
                }
                i++;
            }
            return finalStatus;
        }finally{
            releaseLocksForKvBatch(lockPairs);
        }
    }

    private void releaseLocksForKvBatch(Pair<KVPair, Lock>[] locks){
        if(locks==null) return;
        for(Pair<KVPair, Lock> lock : locks){
            if(lock==null || lock.getSecond()==null) continue;
            lock.getSecond().unlock();
        }
    }

    /*
    private DataScanner getRedoLogScanner(Partition table, UnsafeRecord unsafeRecord,DataResult[] possibleConflicts) throws IOException {
        return table.openScanner(opFactory
                .newScan()
                .setFamily(SIConstants.DEFAULT_FAMILY_REDO_BYTES)
                .startKey(unsafeRecord.getKey())
                .filter(new SimpleRedoTxnFilter())
    }
    */

    private IntObjectOpenHashMap<DataPut> checkConflictsForKvBatch(Partition table,
                                                                   RollForward rollForwardQueue,
                                                                   Pair<KVPair, Lock>[] dataAndLocks,
                                                                   LongOpenHashSet[] conflictingChildren,
                                                                   TxnView transaction,
                                                                   byte[] family, byte[] qualifier,
                                                                   ConstraintChecker constraintChecker,
                                                                   MutationStatus[] finalStatus, boolean skipConflictDetection,
                                                                   boolean skipWAL,
                                                                   ExecRow execRow) throws IOException {
        SpliceQuery spliceQuery = new SpliceQuery(execRow);
        IntObjectOpenHashMap<DataPut> finalMutationsToWrite = IntObjectOpenHashMap.newInstance(dataAndLocks.length, 0.9f);
        // 1 Bloom In Memory Check
        BitSet bloomInMemoryCheck  = skipConflictDetection ? null : table.getBloomInMemoryCheck(constraintChecker!=null,dataAndLocks);
        // 2 Grab Record from Active Side if Available
        DataResult[] possibleConflicts = getPossibleConflicts(table,dataAndLocks,constraintChecker,skipConflictDetection,spliceQuery);
        // 3 Write/Write Check/Isolation Level Switch (TODO JL)
        ConflictResults[] conflictResults=ensureNoWriteConflict(transaction,possibleConflicts); // Need to Parallelize txn resolution

        DataResult[] actualData = null;
        if (possibleConflicts != null) {
            // Fetch Transactions and Resolve (TODO JL HeavyWeight, Parallelize)
            TxnView[] txns = fetchTransactions(transaction, possibleConflicts, txnSupplier);
            // Transform for writes (Put with multiple families, etc.)
            // applyRedoLogsIfNeeded(table, txns, possibleConflicts.getFirst(), transaction); (Not Needed)
            // Apply Constraints

            DataResult[] actualConflicts = resolvePossibleConflictsToActual(table,transaction,txns,possibleConflicts,dataAndLocks,spliceQuery);
            applyConstraint(constraintChecker,dataAndLocks,actualConflicts,finalStatus,null,transaction,table,spliceQuery); // TODO Additive Conflict?

        }

        // Additive Check?
        for (int i = 0; i< dataAndLocks.length; i++) {
            if(KVPair.Type.UPSERT.equals(dataAndLocks[i].getFirst().getType())){
                    /*
                     * If the type is an upsert, then we want to check for an ADDITIVE conflict. If so,
                     * we fail this row with an ADDITIVE_UPSERT_CONFLICT.
                     */
                    if(conflictResults[i].hasAdditiveConflicts()){
                        finalStatus[i]=operationStatusLib.failure(exceptionLib.additiveWriteConflict());
                    }
                }
        }
        for (int i = 0; i< dataAndLocks.length; i++) {
            if (finalStatus[i] == null) {
                DataPut mutationToRun = getMutationToRun(table, rollForwardQueue, dataAndLocks[i].getFirst(), possibleConflicts == null ? null : possibleConflicts[i],
                        family, qualifier, transaction, conflictResults == null ? ConflictResults.NO_CONFLICT : conflictResults[i], skipWAL, execRow);
                finalMutationsToWrite.put(i, mutationToRun);
            }
        }
        return finalMutationsToWrite;
    }

    public DataResult[] resolvePossibleConflictsToActual(Partition table, TxnView transaction, TxnView[] txns, DataResult[] possibleConflicts,
                                                         Pair<KVPair, Lock>[] dataAndLocks,SpliceQuery spliceQuery) throws IOException {
        try {
            DataResult[] actual = new DataResult[possibleConflicts.length];
            for (int i = 0; i < possibleConflicts.length; i++) {
                if (txns[i] != null) {
                    if (txns[i].getEffectiveState().isFinal()) {
                        UnsafeRecord unsafeRecord = new UnsafeRecord(); // Need to pass vs. creating each time.  Waste of resources.
                        unsafeRecord.wrap(possibleConflicts[i].activeData());
                        unsafeRecord.setEffectiveTimestamp((txns[i].getEffectiveState() == Txn.State.ROLLEDBACK) ? -1L:txns[i].getEffectiveCommitTimestamp());
                    }
                    if (txns[i].getEffectiveState() != Txn.State.ROLLEDBACK)
                        actual[i] = possibleConflicts[i];
                    else {
                        long version = possibleConflicts[i].activeData().version();
                        actual[i] = ScannerFunctionUtils.transformResult(possibleConflicts[i], transaction, txnSupplier, table, spliceQuery, opFactory, txnOperationFactory);
                        if (actual[i] == null | actual[i].activeData() == null) {
                            continue;
                        }
                        KVPair currentUpdate = dataAndLocks[i].getFirst();
                        UnsafeRecord currentUpdateRecord = new UnsafeRecord();
                        currentUpdateRecord.wrap(currentUpdate, true, 1);
                        UnsafeRecord actualRecord = new UnsafeRecord();
                        assert actual[i].activeData() != null:"data is null " + actual[i];
                        actualRecord.wrap(actual[i].activeData());
                        Record update = actualRecord.applyRedo(currentUpdateRecord, spliceQuery.getTemplate());
                        update.setVersion(version);
                        dataAndLocks[i] = Pair.newPair(update.getKVPair(),dataAndLocks[i].getSecond());
                    }
                }
            }
            return actual;
        } catch (StandardException se) {
            throw new IOException(se);
        }
    }


    private BitSet applyConstraint(ConstraintChecker constraintChecker,
                                   Pair<KVPair, Lock>[] mutations,
                                    DataResult[] possibleConflicts,
                                    MutationStatus[] finalStatus,
                                    boolean[] additiveConflict,
                                   TxnView currentTxn,
                                   Partition table,
                                   SpliceQuery spliceQuery) throws IOException{
        /*
         * Attempts to apply the constraint (if there is any). When this method returns true, the row should be filtered
         * out.
         */
        if(constraintChecker==null)
            return null;
        if (possibleConflicts == null)
            return null;
        BitSet bitSet = new BitSet(mutations.length);
        for (int i = 0; i< possibleConflicts.length; i++) {
            if (possibleConflicts[i] == null)
                continue;
            DataResult actualResult = ScannerFunctionUtils.transformResult(possibleConflicts[i],currentTxn,txnSupplier,table,spliceQuery,opFactory,txnOperationFactory);
            if (actualResult != null && actualResult.activeData()!=null) {
                MutationStatus operationStatus = constraintChecker.checkConstraint(mutations[i].getFirst(), actualResult);
                if (operationStatus != null && !operationStatus.isSuccess()) {
                    finalStatus[i] = operationStatus;
                }
            }
        }
        return bitSet;
    }

        /**
         * We attempt to lock each row in the collection.
         *
         * If the lock is acquired, we place it into mutationsAndLocks (at the position equal
         * to the position in the collection's iterator).
         *
         * If the lock cannot be acquired, then we set NOT_RUN into the finalStatus array. Those rows will be filtered
         * out and must be retried by the writer. mutationsAndLocks at the same location will be null
         **/
    private void lockRows(Partition table,Collection<KVPair> mutations,Pair<KVPair, Lock>[] mutationsAndLocks,MutationStatus[] finalStatus) throws IOException{
        int position=0;
        try{
            for(KVPair mutation : mutations){
                ByteSlice byteSlice=mutation.rowKeySlice();
                Lock lock=table.getRowLock(byteSlice.array(),byteSlice.offset(),byteSlice.length());//tableWriter.getRowLock(table, mutation.rowKeySlice());
                if(lock.tryLock())
                    mutationsAndLocks[position]=Pair.newPair(mutation,lock);
                else
                    finalStatus[position]=operationStatusLib.notRun();

                position++;
            }
        }catch(RuntimeException re){
            /*
             * trying the lock can result in us throwing a NotServingRegionException etc, which is wrapped
             * by the RuntimeException. Thus, we want to convert all RuntimeErrors to IOExceptions (if possible)
             *
             * todo -sf- I'm not sure if this is correct around OutOfMemoryError and other stuff like that
             */
            throw exceptionLib.processRemoteException(re);
        }
    }

    private void printoutPair (KVPair kvPair, ExecRow execRow, String phase, Partition table) throws StandardException {
        try {
            UnsafeRecord unsafeRecord = new UnsafeRecord();
            unsafeRecord.wrap(kvPair, true, 1); // Does Version Change?
            printout(unsafeRecord, execRow, phase, table);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void printout (Record unsafeRecord, ExecRow execRow, String phase, Partition table) throws StandardException {
     //       System.out.println(table.getName() + ": " + phase+": " + unsafeRecord);
        if (unsafeRecord.hasTombstone() == false) {
            unsafeRecord.getData(IntArrays.count(execRow.nColumns()), execRow);
    //            System.out.println(table.getName() + ": " + phase + ": " + execRow);
        } else {
     //           System.out.println(table.getName() + ": " + phase + ": deleted -> ");
        }

    }


    private DataPut getMutationToRun(Partition table, RollForward rollForwardQueue, KVPair kvPair,DataResult possibleLegacyRecord,
                                     byte[] family, byte[] column,
                                     TxnView transaction, ConflictResults conflictResults, boolean skipWAL, ExecRow execRow) throws IOException{
        assert execRow!=null:"Exec Row is null";
//                if (LOG.isTraceEnabled()) LOG.trace(String.format("table = %s, kvPair = %s, txnId = %s", table.toString(), kvPair.toString(), txnIdLong));
        DataPut newPut;
        try {
            UnsafeRecord kvPairRecord = new UnsafeRecord();
            kvPairRecord.wrap(kvPair, true, 1); // Does Version Change?
                kvPairRecord.setEffectiveTimestamp(0);
            kvPairRecord.setTxnId1(transaction.getTxnId());
            if (kvPair.getType() == KVPair.Type.DELETE || kvPairRecord.hasTombstone()) {
                if (possibleLegacyRecord == null) {
                    LOG.warn("Delete without record to delete");
                    printoutPair(kvPair,execRow,"Single Delete", table);
                    kvPairRecord.setHasTombstone(true);
                    newPut = opFactory.newPut(kvPair.getRowKey());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,SIConstants.PACKED_COLUMN_BYTES,kvPairRecord.getVersion(),kvPairRecord.getValue());
                } else {
//                    printoutPair(kvPair,execRow,"Delete with record to delete", table);
                    kvPairRecord.setHasTombstone(true);
                    kvPairRecord.setTxnId1(transaction.getTxnId());
                    UnsafeRecord unsafeRecord = new UnsafeRecord();
                    unsafeRecord.wrap(possibleLegacyRecord.activeData());
                    Record[] unsafeRecords = unsafeRecord.updateRecord(kvPairRecord, execRow);
//                    printout(unsafeRecords[0],execRow,"Delete Active Record", table);
//                    printout(unsafeRecords[1],execRow,"Delete Redo Record", table);
                    newPut = opFactory.newPut(kvPair.getRowKey());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,SIConstants.PACKED_COLUMN_BYTES,unsafeRecords[0].getVersion(),unsafeRecords[0].getValue());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_REDO_BYTES,SIConstants.PACKED_COLUMN_BYTES,unsafeRecords[1].getVersion(),unsafeRecords[1].getValue());
                }
            } else if (kvPair.getType() == KVPair.Type.INSERT) {
                if (possibleLegacyRecord == null) {
//                    printoutPair(kvPair,execRow,"Single Insert", table);
                    newPut = opFactory.newPut(kvPair.getRowKey());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,SIConstants.PACKED_COLUMN_BYTES,kvPairRecord.getVersion(),kvPairRecord.getValue());
                }
                else {
//                    printoutPair(kvPair,execRow,"Update Active Record Insert", table);
                    UnsafeRecord unsafeRecord = new UnsafeRecord();
                    unsafeRecord.wrap(possibleLegacyRecord.activeData());
//                    printout(unsafeRecord,execRow,"Legacy Active Record Insert", table);

                    Record[] unsafeRecords = unsafeRecord.updateRecord(kvPairRecord, execRow);
//                    printout(unsafeRecords[0],execRow,"Insert Active Record", table);
//                    printout(unsafeRecords[1],execRow,"Insert Redo Record", table);
                    newPut = opFactory.newPut(kvPair.getRowKey());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,SIConstants.PACKED_COLUMN_BYTES,unsafeRecords[0].getVersion(),unsafeRecords[0].getValue());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_REDO_BYTES,SIConstants.PACKED_COLUMN_BYTES,unsafeRecords[1].getVersion(),unsafeRecords[1].getValue());
                }
            } else if (kvPair.getType() == KVPair.Type.UPDATE) {
                if (possibleLegacyRecord == null) {
  //                  printoutPair(kvPair,execRow,"Single Update", table);
                    newPut = opFactory.newPut(kvPair.getRowKey());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,SIConstants.PACKED_COLUMN_BYTES,kvPairRecord.getVersion(),kvPairRecord.getValue());
                }
                else {
                    UnsafeRecord unsafeRecord = new UnsafeRecord();
                    unsafeRecord.wrap(possibleLegacyRecord.activeData());
                    Record[] unsafeRecords = unsafeRecord.updateRecord(kvPairRecord, execRow);
   //                 printout(unsafeRecords[0],execRow,"Update Active Record", table);
   //                 printout(unsafeRecords[1],execRow,"Update Redo Record", table);
                    newPut = opFactory.newPut(kvPair.getRowKey());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES,SIConstants.PACKED_COLUMN_BYTES,unsafeRecords[0].getVersion(),unsafeRecords[0].getValue());
                    newPut.addCell(SIConstants.DEFAULT_FAMILY_REDO_BYTES,SIConstants.PACKED_COLUMN_BYTES,unsafeRecords[1].getVersion(),unsafeRecords[1].getValue());
                }
            } else {
                throw new UnsupportedOperationException("Not Supported yet");
            }
            newPut.addAttribute(SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_NAME, SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_VALUE);
            if (skipWAL)
                newPut.skipWAL();
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e);
        }
        return newPut;
    }

    private void resolveChildConflicts(Partition table,DataPut put,LongOpenHashSet conflictingChildren) throws IOException{
        if(conflictingChildren!=null && !conflictingChildren.isEmpty()){
            DataDelete delete=opFactory.newDelete(put.key());
            Iterable<DataCell> cells=put.cells();
            for(LongCursor lc : conflictingChildren){
                for(DataCell dc : cells){
                    delete.deleteColumn(dc.family(),dc.qualifier(),lc.value);
                }
                delete.deleteColumn(SIConstants.DEFAULT_FAMILY_BYTES,SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES,lc.value);
                delete.deleteColumn(SIConstants.DEFAULT_FAMILY_BYTES,SIConstants.SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES,lc.value);
            }
            delete.addAttribute(SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_NAME,SIConstants.SUPPRESS_INDEXING_ATTRIBUTE_VALUE);
            table.delete(delete);
        }
    }

    /**
     * While we hold the lock on the row, check to make sure that no transactions have updated the row since the
     * updating transaction started.
     */

    private ConflictResults[] ensureNoWriteConflict(TxnView updateTransaction, DataResult[] possibleConflicts) throws IOException{
        if (possibleConflicts == null)
            return null;

        ConflictResults[] conflictResults = new ConflictResults[possibleConflicts.length];
        UnsafeRecord unsafeRecord = new UnsafeRecord();
        for (int i = 0; i < possibleConflicts.length; i++) {
            if (possibleConflicts[i] == null) {
                conflictResults[i] = ConflictResults.NO_CONFLICT;
                continue;
            }
            unsafeRecord.wrap(possibleConflicts[i].activeData());
            conflictResults[i]=checkDataForConflict(updateTransaction,conflictResults[i],possibleConflicts[i].activeData(),unsafeRecord.getTxnId1());
        }
        return conflictResults;
    }

    private ConflictResults checkDataForConflict(TxnView updateTransaction,
                                                 ConflictResults conflictResults,
                                                 DataCell cell,
                                                 long dataTransactionId) throws IOException{
        if(updateTransaction.getTxnId()!=dataTransactionId){
            final TxnView dataTransaction=txnSupplier.getTransaction(updateTransaction,dataTransactionId);
            if(dataTransaction.getState()==Txn.State.ROLLEDBACK){
                return conflictResults; //can't conflict with a rolled back transaction
            }
            final ConflictType conflictType=updateTransaction.conflicts(dataTransaction);
            switch(conflictType){
                case CHILD:
                    if(conflictResults==null){
                        conflictResults=new ConflictResults();
                    }
                    conflictResults.addChild(dataTransactionId);
                    break;
                case ADDITIVE:
                    if(conflictResults==null){
                        conflictResults=new ConflictResults();
                    }
                    conflictResults.addAdditive(dataTransactionId);
                    break;
                case SIBLING:
                    if(LOG.isTraceEnabled()){
                        SpliceLogUtils.trace(LOG,"Write conflict on row "
                                +Bytes.toHex(cell.keyArray(),cell.keyOffset(),cell.keyLength()));
                    }
                    throw exceptionLib.writeWriteConflict(dataTransactionId,updateTransaction.getTxnId());
            }
        }
        return conflictResults;
    }

    // Helpers

    private boolean isFlaggedForSITreatment(Attributable operation){
        return operation.getAttribute(SIConstants.SI_NEEDED)!=null;
    }


    private void ensureTransactionAllowsWrites(TxnView transaction) throws IOException{
        if(transaction==null || !transaction.allowsWrites()){
            throw exceptionLib.readOnlyModification("transaction is read only: "+transaction);
        }
    }

    private void setTombstonesOnColumns(Partition table, long timestamp, DataPut put) throws IOException {
        //-sf- this doesn't really happen in practice, it's just for a safety valve, which is good, cause it's expensive
        final Map<byte[], byte[]> userData = getUserData(table,put.key());
        if (userData != null) {
            for (byte[] qualifier : userData.keySet()) {
                put.addCell(SIConstants.PACKED_COLUMN_BYTES,qualifier,timestamp,SIConstants.EMPTY_BYTE_ARRAY);
            }
        }
    }

    private Map<byte[], byte[]> getUserData(Partition table, byte[] rowKey) throws IOException {
        DataResult dr = table.getLatest(rowKey,SIConstants.PACKED_COLUMN_BYTES,null);
        if (dr != null) {
            return dr.familyCellMap(SIConstants.PACKED_COLUMN_BYTES);
        }
        return null;
    }

    /**
     *
     * Return The Possible Conflicts
     *
     * @param table
     * @param dataAndLocks
     * @param constraintChecker
     * @param skipConflictDetection
     * @return
     * @throws IOException
     */
    public DataResult[] getPossibleConflicts(Partition table,Pair<KVPair, Lock>[] dataAndLocks,ConstraintChecker constraintChecker,
                                             boolean skipConflictDetection, SpliceQuery spliceQuery) throws IOException {

        BitSet bloomInMemoryCheck = skipConflictDetection ? null : table.getBloomInMemoryCheck(constraintChecker != null, dataAndLocks);
        DataResult[] possibleConflicts = null;
        for (int i = 0; i < dataAndLocks.length; i++) {
            Pair<KVPair, Lock> baseDataAndLock = dataAndLocks[i];
            if (baseDataAndLock == null) {
                continue;
            }
            KVPair kvPair = baseDataAndLock.getFirst();
            KVPair.Type writeType = kvPair.getType();
            if (!skipConflictDetection && (constraintChecker != null || !KVPair.Type.INSERT.equals(writeType))) {
                /*
                 *
                 * If the table has no keys, then the hbase row key is a randomly generated UUID, so it's not
                 * going to incur a write/write penalty, because there isn't any other row there (as long as we are inserting).
                 * Therefore, we do not need to perform a write/write conflict check or a constraint check
                 *
                 * We know that this is the case because there is no constraint checker (constraint checkers are only
                 * applied on key elements.
                 */
                DataResult dr = null;
                DataScan dataScan = null;
                DataScanner ds = null;


                if (bloomInMemoryCheck == null || bloomInMemoryCheck.get(i)) {
                    if (dataScan == null) {
                        dataScan = opFactory.newScan();
                        dataScan.setFamily(SIConstants.DEFAULT_FAMILY_ACTIVE_BYTES);
                        dataScan.startKey(kvPair.getRowKey());
                        dataScan.cacheRows(1);
                        ds = table.openScanner(dataScan);
                        queryContext.set(spliceQuery);
                    } else {
                        ds.reseek(kvPair.getRowKey());
                    }
                    dr = opFactory.newResult(ds.next(1));
                    if (dr != null && dr.activeData() != null && Bytes.equals(dr.key(),kvPair.getRowKey())) {
                        if (possibleConflicts == null)
                            possibleConflicts = new DataResult[dataAndLocks.length];
                        possibleConflicts[i] = dr;
                    }
                }
                if (ds != null)
                    ds.close();
            }
        }

        return possibleConflicts;
    }




        /*
        LongSet txnSet = null;
        try {
            for (Record record : records) {
                // Empty Array Element, Txn Resolved (Committed or Rolledback) (1)
                if (record == null || record.getEffectiveTimestamp() != 0)
                    break;

                // Collapsable Txn (2)
                if (record.getTxnId2() < 0) {
                    Txn txn = txnSupplier.getTransaction(record.getTxnId1());
                    if (txn == null) { // Txn cannot be looked up in cache
                        if (txnSet==null)
                            txnSet = new LongOpenHashSet();
                        txnSet.add(record.getTxnId1());
                        continue;
                    }
                    txn.resolveCollapsibleTxn(record,activeTxn,txn);
                }
                // Hierarchical Txn (3)
                if (record.getTxnId1() > record.getTxnId2()) {
                    Txn childTxn = txnSupplier.getTransaction(record.getTxnId1());
                    Txn parentTxn = txnSupplier.getTransaction(record.getTxnId2());
                    if (childTxn == null) {
                        if (txnSet == null)
                            txnSet = new LongOpenHashSet();
                        txnSet.add(record.getTxnId1());
                    }
                    if (parentTxn == null) {
                        if (txnSet == null)
                            txnSet = new LongOpenHashSet();
                        txnSet.add(record.getTxnId2());
                    } else {


                    }


                }
            }
            return txnSet;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        */

    /**
     *
     * Retrieves the txns for a an array of DataResult
     *
     * @param possibleConflicts
     * @return
     */
    public TxnView[] fetchTransactions (TxnView currentTxn, DataResult[] possibleConflicts, TxnSupplier txnSupplier) throws IOException {
        if (possibleConflicts == null)
            return null;
        UnsafeRecord unsafeRecord = new UnsafeRecord();
        TxnView[] txns = new TxnView[possibleConflicts.length];
        for (int i = 0; i< possibleConflicts.length; i++) {
            if (possibleConflicts[i] == null)
                continue;
            unsafeRecord.wrap(possibleConflicts[i].activeData());
            if (unsafeRecord.isResolved()) {
                txns[i] = txnSupplier.getTransaction(currentTxn, unsafeRecord.getTxnId1()); // TODO FIX JL
            }
            else {
                txns[i] = txnSupplier.getTransaction(currentTxn, unsafeRecord.getTxnId1());
            }
        }
        return txns;
    }


    /**
     * First, we check to see if we are covered by a tombstone--that is,
     * if keyValue has a timestamp <= the timestamp of a tombstone AND our isolationLevel
     * allows us to see the tombstone, then this row no longer exists for us, and
     * we should just skip the column.
     *
     * Otherwise, we just look at the transaction of the entry's visibility to us--if
     * it matches, then we can see it.
     */
    public static DataFilter.ReturnCode checkVisibility(UnsafeRecord ur, TxnView activeTxn, TxnSupplier txnSupplier) throws IOException{
        if(!isVisible(ur.getTxnId1(), activeTxn, txnSupplier))
            return ur.getVersion() == 1? DataFilter.ReturnCode.NEXT_ROW: DataFilter.ReturnCode.SKIP;
        return ur.hasTombstone()? DataFilter.ReturnCode.NEXT_ROW: DataFilter.ReturnCode.INCLUDE;
    }

    public static boolean isVisible(long txnId,TxnView activeTxn, TxnSupplier txnSupplier) throws IOException{
        TxnView toCompare=fetchTransaction(txnId,activeTxn, txnSupplier);
        // If the database is restored from a backup, it may contain data that were written by a transaction which
        // is not present in SPLICE_TXN table, because SPLICE_TXN table is copied before the transaction begins.
        // However, the table written by the txn was copied
        return toCompare != null ? activeTxn.canSee(toCompare) : false;
    }

    public static TxnView fetchTransaction(long txnId,TxnView activeTxn, TxnSupplier txnSupplier) throws IOException{
        return activeTxn==null || activeTxn.getTxnId()!=txnId?txnSupplier.getTransaction(activeTxn,txnId):activeTxn;
    }


}
