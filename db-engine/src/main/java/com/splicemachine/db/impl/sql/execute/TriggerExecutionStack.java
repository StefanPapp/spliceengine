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
 * All such Splice Machine modifications are Copyright 2012 - 2018 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.db.impl.sql.execute;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.List;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.Limits;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.services.sanity.SanityManager;
import org.apache.log4j.Logger;

/**
 * A trigger execution stack holds a stack of {@link TriggerExecutionContext}s.<br/>
 * This class is pulled out of LCC for serialization.
 */
public class TriggerExecutionStack implements Externalizable {
    private static final Logger LOG = Logger.getLogger(TriggerExecutionStack.class);
    private List<TriggerExecutionContext> triggerExecutionContexts = new ArrayList<>();

    public List<TriggerExecutionContext> asList() {
        return this.triggerExecutionContexts;
    }

    /**
     * Push a new trigger execution context.  Multiple TriggerExecutionContexts may be active at any given time.
     *
     * @param tec the trigger execution context
     *
     * @exception StandardException on trigger recursion error
     */
    public void pushTriggerExecutionContext(TriggerExecutionContext tec) throws StandardException {
            /* Maximum 16 nesting levels allowed */
        if (triggerExecutionContexts.size() >= Limits.DB2_MAX_TRIGGER_RECURSION) {
            throw StandardException.newException(SQLState.LANG_TRIGGER_RECURSION_EXCEEDED);
        }
        triggerExecutionContexts.add(tec);
        }

    /**
     * Remove the tec.  Does an object identity (tec == tec) comparison.  Asserts that the tec is found.
     *
     * @param tec the tec to remove
     */
    public void popTriggerExecutionContext(TriggerExecutionContext tec) throws StandardException {
        if (triggerExecutionContexts.isEmpty()) {
            return;
        }
        boolean foundElement = triggerExecutionContexts.remove(tec);
        if (SanityManager.DEBUG) {
            if (!foundElement) {
                SanityManager.THROWASSERT("trigger execution context "+tec+" not found");
            }
        }
    }

    /**
     * Pop all TriggerExecutionContexts off the stack. This usually means an error occurred.
     */
    public void popAllTriggerExecutionContexts() {
        if (triggerExecutionContexts.isEmpty()) {
            return;
        }
        triggerExecutionContexts.clear();
    }

    /**
     * Get the topmost tec.
     */
    public TriggerExecutionContext getTriggerExecutionContext() {
        return triggerExecutionContexts.isEmpty() ? null :
            triggerExecutionContexts.get(triggerExecutionContexts.size() - 1);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(triggerExecutionContexts);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        try {
            triggerExecutionContexts = (List<TriggerExecutionContext>) in.readObject();
        } catch (Throwable t) {
            LOG.error("Unexpected exception during deserialization", t);
            throw t;
        }
    }

    public boolean isEmpty() {
        return this.triggerExecutionContexts.isEmpty();
    }
}
