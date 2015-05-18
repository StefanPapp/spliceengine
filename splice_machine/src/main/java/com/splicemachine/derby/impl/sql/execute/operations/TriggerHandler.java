package com.splicemachine.derby.impl.sql.execute.operations;

import com.google.common.collect.Lists;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.jdbc.ConnectionContext;
import com.splicemachine.db.iapi.services.context.Context;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.ResultDescription;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.impl.jdbc.EmbedConnection;
import com.splicemachine.db.impl.sql.execute.TriggerEvent;
import com.splicemachine.db.impl.sql.execute.TriggerEventActivator;
import com.splicemachine.db.impl.sql.execute.TriggerInfo;
import com.splicemachine.derby.iapi.sql.execute.SingleRowCursorResultSet;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.impl.sql.execute.actions.WriteCursorConstantOperation;
import com.splicemachine.tools.EmbedConnectionMaker;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Used by DMLOperation to initialize the derby classes necessary for firing row/statement triggers.  Also provides
 * convenient methods for firing.
 */
public class TriggerHandler {

    private static final int AFTER_ROW_LIMIT = 1000;

    private TriggerEventActivator triggerActivator;
    private ResultDescription resultDescription;
    private TriggerEvent beforeEvent;
    private TriggerEvent afterEvent;
    private List<ExecRow> pendingAfterRows;

    private final boolean hasBeforeRow;
    private final boolean hasBeforeStatement;
    private final boolean hasAfterRow;
    private final boolean hasAfterStatement;

    public TriggerHandler(SpliceOperationContext context,
                          TriggerInfo triggerInfo,
                          DMLWriteInfo writeInfo,
                          Activation activation,
                          TriggerEvent beforeEvent,
                          TriggerEvent afterEvent) throws StandardException {
        WriteCursorConstantOperation constantAction = (WriteCursorConstantOperation) writeInfo.getConstantAction();
        initConnectionContext(context);
        this.beforeEvent = beforeEvent;
        this.afterEvent = afterEvent;
        this.resultDescription = activation.getResultDescription();
        this.pendingAfterRows = Lists.newArrayListWithCapacity(AFTER_ROW_LIMIT);

        this.hasBeforeRow = triggerInfo.hasBeforeRowTrigger();
        this.hasAfterRow = triggerInfo.hasAfterRowTrigger();
        this.hasBeforeStatement = triggerInfo.hasBeforeStatementTrigger();
        this.hasAfterStatement = triggerInfo.hasAfterStatementTrigger();

        this.triggerActivator = new TriggerEventActivator(
                context.getLanguageConnectionContext(),
                context.getLanguageConnectionContext().getTransactionExecute(),
                constantAction.getTargetUUID(),
                constantAction.getTriggerInfo(),
                activation,
                null);
    }

    /* We have a trigger and we are on a region-side node where the LCC has no connection context.  Add one.
     * Is there a better way to do this? */
    private void initConnectionContext(SpliceOperationContext context) throws StandardException {
        LanguageConnectionContext lcc = context.getLanguageConnectionContext();
        ConnectionContext existingContext = (ConnectionContext) lcc.getContextManager().getContext(ConnectionContext.CONTEXT_ID);
        if (existingContext == null) {
            try {
                Connection connection = new EmbedConnectionMaker().createNew();
                Context newContext = ((EmbedConnection) connection).getContextManager().getContext(ConnectionContext.CONTEXT_ID);
                lcc.getContextManager().pushContext(newContext);
            } catch (SQLException e) {
                throw StandardException.plainWrapException(e);
            }
        }
    }

    public void cleanup() throws StandardException {
        if (triggerActivator != null) {
            triggerActivator.cleanup();
        }
    }

    public void fireBeforeRowTriggers(ExecRow row) throws StandardException {
        if (row != null && hasBeforeRow) {
            SingleRowCursorResultSet rowResultSet1 = new SingleRowCursorResultSet(resultDescription, row);
            SingleRowCursorResultSet rowResultSet2 = new SingleRowCursorResultSet(resultDescription, row);
            triggerActivator.notifyRowEvent(beforeEvent, rowResultSet1, rowResultSet2, null);
        }
    }

    public void fireAfterRowTriggers(ExecRow row, Callable<Void> flushCallback) throws Exception {
        pendingAfterRows.add(row.getClone());
        if (pendingAfterRows.size() == AFTER_ROW_LIMIT) {
            firePendingAfterTriggers(flushCallback);
        }
    }

    public void firePendingAfterTriggers(Callable<Void> flushCallback) throws Exception {
        flushCallback.call();
        for (ExecRow flushedRow : pendingAfterRows) {
            fireAfterRowTriggers(flushedRow);
        }
        pendingAfterRows.clear();
    }

    private void fireAfterRowTriggers(ExecRow row) throws StandardException {
        if (row != null && hasAfterRow) {
            SingleRowCursorResultSet rowResultSet1 = new SingleRowCursorResultSet(resultDescription, row);
            SingleRowCursorResultSet rowResultSet2 = new SingleRowCursorResultSet(resultDescription, row);
            triggerActivator.notifyRowEvent(afterEvent, rowResultSet1, rowResultSet2, null);
        }
    }

    public void fireBeforeStatementTriggers() throws StandardException {
        if (hasBeforeStatement) {
            triggerActivator.notifyStatementEvent(beforeEvent);
        }
    }

    public void fireAfterStatementTriggers() throws StandardException {
        if (hasAfterStatement) {
            triggerActivator.notifyStatementEvent(afterEvent);
        }
    }

}
