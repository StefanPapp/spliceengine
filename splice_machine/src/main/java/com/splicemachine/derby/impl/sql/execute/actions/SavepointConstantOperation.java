/*
 * Copyright (c) 2012 - 2020 Splice Machine, Inc.
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

package com.splicemachine.derby.impl.sql.execute.actions;

import com.splicemachine.db.iapi.sql.execute.ConstantAction;
import com.splicemachine.db.iapi.sql.conn.LanguageConnectionContext;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.sql.conn.StatementContext;
import com.splicemachine.db.iapi.reference.SQLState;

/**
 *	This class  describes actions that are ALWAYS performed for a
 *	Savepoint (rollback, release and set savepoint) Statement at Execution time.
 */

public class SavepointConstantOperation extends DDLConstantOperation {
    private static enum SavepointType{
        CREATE(1),
        ROLLBACK(2),
        RELEASE(3);
        private final int statementTypeCode; //the code generated by the parser

        SavepointType(int statementTypeCode) {
            this.statementTypeCode = statementTypeCode;
        }

        public static SavepointType forCode(int code){
            for(SavepointType type:values()){
                if(type.statementTypeCode==code) return type;
            }
            throw new IllegalArgumentException("Unknown code for SavepointType : " + code);
        }
    }
	private final String savepointName; //name of the savepoint
	private final SavepointType	savepointStatementType; //Type of savepoint statement ie rollback, release or set savepoint
	/**
	 *	Make the ConstantAction for a set savepoint, rollback or release statement.
	 *
	 *  @param savepointName	Name of the savepoint.
	 *  @param savepointStatementType	set savepoint, rollback savepoint or release savepoint
	 */
	public SavepointConstantOperation(String savepointName, int savepointStatementType) {
		this.savepointName = savepointName;
		this.savepointStatementType = SavepointType.forCode(savepointStatementType);
	}

	// OBJECT METHODS
	public	String	toString() {
    switch(savepointStatementType){
        case CREATE:
            return constructToString("SAVEPOINT ",savepointName);
        case ROLLBACK:
            return constructToString("ROLLBACK TO SAVEPOINT",savepointName);
        default:
            return constructToString("RELEASE TO SAVEPOINT",savepointName);
    }
	}

    /**
     *	This is the guts of the Execution-time logic for CREATE TABLE.
     *
     *	@see ConstantAction#executeConstantAction
     *
     * @exception StandardException		Thrown on failure
     */
    public void executeConstantAction( Activation activation ) throws StandardException {
        LanguageConnectionContext lcc = activation.getLanguageConnectionContext();
        //Bug 4507 - savepoint not allowed inside trigger
        StatementContext stmtCtxt = lcc.getStatementContext();
        if (stmtCtxt!= null && stmtCtxt.inTrigger())
            throw StandardException.newException(SQLState.NO_SAVEPOINT_IN_TRIGGER);
        switch(savepointStatementType){
            case CREATE:
                if (savepointName.startsWith("SYS")) //to enforce DB2 restriction which is savepoint name can't start with SYS
                    throw StandardException.newException(SQLState.INVALID_SCHEMA_SYS, "SYS");
                lcc.languageSetSavePoint(savepointName, savepointName);
                break;
            case ROLLBACK:
                lcc.internalRollbackToSavepoint(savepointName,true, savepointName);
                break;
            case RELEASE:
                lcc.releaseSavePoint(savepointName, savepointName);
                break;
            default:
                throw new IllegalStateException("Programmer Error: Unknown statement type " + savepointStatementType);
        }
    }

}
