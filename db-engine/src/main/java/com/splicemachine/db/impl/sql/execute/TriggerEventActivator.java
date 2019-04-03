/*
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Some parts of this source code are based on Apache Derby, and the following notices apply to
 * Apache Derby:
 *
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified the Apache Derby code in this file.
 *
 * All such Splice Machine modifications are Copyright 2012 - 2019 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.db.impl.sql.execute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

import com.splicemachine.db.catalog.UUID;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.conn.StatementContext;
import com.splicemachine.db.iapi.sql.dictionary.TriggerDescriptor;
import com.splicemachine.db.iapi.sql.execute.CursorResultSet;

/**
 * Responsible for firing a trigger or set of triggers based on an event.
 */
public class TriggerEventActivator {

    private LanguageConnectionContext lcc;
    private TriggerInfo triggerInfo;
    private TriggerExecutionContext tec;
    private Map<TriggerEvent, List<TriggerDescriptor>> statementExecutorsMap = new HashMap<>();
    private Map<TriggerEvent, List<TriggerDescriptor>> rowExecutorsMap = new HashMap<>();
    private Map<TriggerEvent, List<TriggerDescriptor>> rowConcurrentExecutorsMap = new HashMap<>();
    private Activation activation;
    private String statementText;
    private UUID tableId;
    private String tableName;
    private boolean tecPushed;
    private ExecutorService executorService;
    private Function<Function<LanguageConnectionContext,Void>, Callable> withContext;

    /**
     * Basic constructor
     * @param tableId     UUID of the target table
     * @param triggerInfo the trigger information
     * @param activation  the activation.
     * @param aiCounters  vector of ai counters
     * @param executorService
     * @param withContext
     */
    public TriggerEventActivator(UUID tableId,
                                 TriggerInfo triggerInfo,
                                 Activation activation,
                                 Vector<AutoincrementCounter> aiCounters, ExecutorService executorService, Function<Function<LanguageConnectionContext,Void>, Callable> withContext) throws StandardException {
        if (triggerInfo == null) {
            return;
        }
        // extrapolate the table name from the triggerdescriptors
        this.tableName = triggerInfo.getTriggerDescriptors()[0].getTableDescriptor().getQualifiedName();
        this.activation = activation;
        this.tableId = tableId;
        this.triggerInfo = triggerInfo;
        this.lcc = activation.getLanguageConnectionContext();
        StatementContext context = lcc.getStatementContext();
        if (context != null) {
            this.statementText = context.getStatementText();
        }

        initTriggerExecContext(aiCounters);
        setupExecutors(triggerInfo);
        this.executorService = executorService;
        this.withContext = withContext;
    }

    private void initTriggerExecContext(Vector<AutoincrementCounter> aiCounters) throws StandardException {
        GenericExecutionFactory executionFactory = (GenericExecutionFactory) lcc.getLanguageConnectionFactory().getExecutionFactory();
        this.tec = executionFactory.getTriggerExecutionContext(
               statementText, triggerInfo.getColumnIds(), triggerInfo.getColumnNames(),
                tableId, tableName, aiCounters);
    }

    /**
     * Reopen the trigger activator.  Just creates a new trigger execution context.  Note that close() still must be
     * called when you are done -- you cannot just do a reopen() w/o a first doing a close.
     */
    void reopen() throws StandardException {
        initTriggerExecContext(null);
        setupExecutors(triggerInfo);
    }

    private void setupExecutors(TriggerInfo triggerInfo) throws StandardException {
        for (TriggerDescriptor td : triggerInfo.getTriggerDescriptors()) {
            TriggerEvent event = td.getTriggerEvent();
            if (td.isRowTrigger()) {
                String sql = td.getTriggerDefinition().toUpperCase();
                if ((sql.contains("INSERT") && sql.contains("INTO")) ||
                        (sql.contains("UPDATE") && sql.contains("SET")) ||
                        (sql.contains("DELETE") && sql.contains("FROM"))) {
                    addToMap(rowExecutorsMap, event, td);
                } else {
                    addToMap(rowConcurrentExecutorsMap, event, td);
                }
            } else {
                addToMap(statementExecutorsMap, event, td);
            }
        }
    }

    /**
     * Handle the given statement event.
     *
     * @param event a trigger event
     */
    public void notifyStatementEvent(TriggerEvent event) throws StandardException {

        if (statementExecutorsMap.isEmpty()) {
            return;
        }
        List<TriggerDescriptor> triggerExecutors = statementExecutorsMap.get(event);
        if (triggerExecutors == null || triggerExecutors.isEmpty()) {
            return;
        }

        try {
            lcc.pushExecutionStmtValidator(tec);
            if (! tecPushed) {
                lcc.pushTriggerExecutionContext(tec);
                tecPushed = true;
            }

            for (TriggerDescriptor td : triggerExecutors) {

                StatementTriggerExecutor triggerExecutor = new StatementTriggerExecutor(tec, td, activation, lcc);
                // Reset the AI counters to the beginning before firing next trigger.
                tec.resetAICounters(true);
                // Fire the statement or row trigger.
                triggerExecutor.fireTrigger(event, null, null);
            }
        } finally {
            lcc.popExecutionStmtValidator(tec);
        }
    }

    /**
     * Handle the given row event.
     *
     * @param event             a trigger event
     * @param rs                the triggering result set.  Typically a TemporaryRowHolderResultSet but sometimes a BulkTableScanResultSet
     * @param colsReadFromTable columns required from the trigger table by the triggering sql
     */
    public void notifyRowEvent(TriggerEvent event,
                               CursorResultSet rs,
                               int[] colsReadFromTable,
                               LanguageConnectionContext lcc) throws StandardException {
        if (lcc == null)
            lcc = this.lcc;

        if (rowExecutorsMap.isEmpty()) {
            return;
        }
        List<TriggerDescriptor> triggerExecutors = rowExecutorsMap.get(event);

        boolean sequential = triggerExecutors != null && !triggerExecutors.isEmpty();
        if (!sequential) {
            return;
        }

        try {
            lcc.pushExecutionStmtValidator(tec);
            lcc.pushTriggerExecutionContext(tec);

            for (TriggerDescriptor td : triggerExecutors) {
                RowTriggerExecutor triggerExecutor = new RowTriggerExecutor(tec, td, activation, lcc);
                synchronized(this) {
                    triggerExecutor.forceCompile();
                }

                // Reset the AI counters to the beginning before firing next trigger.
                tec.resetAICounters(true);
                // Fire the statement or row trigger.
                triggerExecutor.fireTrigger(event, rs, colsReadFromTable);
            }
        } finally {
            lcc.popExecutionStmtValidator(tec);
            lcc.popTriggerExecutionContext(tec);
        }
    }

    /**
     * Handle the given row event.
     *
     * @param event             a trigger event
     * @param rs                the triggering result set.  Typically a TemporaryRowHolderResultSet but sometimes a BulkTableScanResultSet
     * @param colsReadFromTable columns required from the trigger table by the triggering sql
     */
    public List<Future<Void>> notifyRowEventConcurrent(TriggerEvent event,
                               CursorResultSet rs,
                               int[] colsReadFromTable) throws StandardException {

        if (rowConcurrentExecutorsMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<TriggerDescriptor> concurrentTriggerExecutors = rowConcurrentExecutorsMap.get(event);

        boolean concurrent = concurrentTriggerExecutors != null && !concurrentTriggerExecutors.isEmpty();
        if (!concurrent) {
            return Collections.emptyList();
        }

        List<Future<Void>> futures = new ArrayList<>();
        GenericExecutionFactory executionFactory = (GenericExecutionFactory) lcc.getLanguageConnectionFactory().getExecutionFactory();
        for (TriggerDescriptor td : concurrentTriggerExecutors) {
            futures.add(executorService.submit(withContext.apply(new Function<LanguageConnectionContext,Void>() {
                @Override
                public Void apply(LanguageConnectionContext lcc) {
                    try {
                        TriggerExecutionContext tec = executionFactory.getTriggerExecutionContext(
                                statementText, triggerInfo.getColumnIds(), triggerInfo.getColumnNames(),
                                tableId, tableName, null);
                        RowTriggerExecutor triggerExecutor = new RowTriggerExecutor(tec, td, activation, lcc);
                        synchronized (TriggerEventActivator.this) {
                            triggerExecutor.forceCompile();
                        }
                        try {

                            lcc.pushExecutionStmtValidator(tec);
                            lcc.pushTriggerExecutionContext(tec);


                            // Reset the AI counters to the beginning before firing next trigger.
                            tec.resetAICounters(true);
                            // Fire the statement or row trigger.
                            triggerExecutor.fireTrigger(event, rs, colsReadFromTable);
                        } finally {
                            lcc.popExecutionStmtValidator(tec);
                            tec.clearTrigger();
                            lcc.popTriggerExecutionContext(tec);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }
            })));
        }

        return futures;
    }


    /**
     * Clean up and release resources.
     */
    public void cleanup() throws StandardException {
        if (tec != null) {
            tec.clearTrigger();
            if (tecPushed) {
                lcc.popTriggerExecutionContext(tec);
            }
            tecPushed = false;
        }
    }

    public LanguageConnectionContext getLcc() {
        return lcc;
    }

    private static void addToMap(Map<TriggerEvent, List<TriggerDescriptor>> map, TriggerEvent event, TriggerDescriptor td) {
        List<TriggerDescriptor> genericTriggerExecutors = map.get(event);
        if (genericTriggerExecutors == null) {
            genericTriggerExecutors = new ArrayList<>();
            map.put(event, genericTriggerExecutors);
        }
        genericTriggerExecutors.add(td);
    }
}
