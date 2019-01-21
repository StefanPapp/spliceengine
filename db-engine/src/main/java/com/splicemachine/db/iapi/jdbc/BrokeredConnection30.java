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

package com.splicemachine.db.iapi.jdbc;

import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
	Extends BrokeredConnection to provide the JDBC 3.0 connection methods.
 */
public class BrokeredConnection30 extends BrokeredConnection
{

	public	BrokeredConnection30(BrokeredConnectionControl control)
            throws SQLException
	{
		super(control);
	}

	public final Statement createStatement(int resultSetType,
                                 int resultSetConcurrency,
                                 int resultSetHoldability)
								 throws SQLException {
		try {
            resultSetHoldability = statementHoldabilityCheck(resultSetHoldability);
			return control.wrapStatement(getRealConnection().createStatement(resultSetType,
                    resultSetConcurrency, resultSetHoldability));
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}
	public final CallableStatement prepareCall(String sql,
                                     int resultSetType,
                                     int resultSetConcurrency,
                                     int resultSetHoldability)
									 throws SQLException {
		try {
            resultSetHoldability = statementHoldabilityCheck(resultSetHoldability);
			return control.wrapStatement(
				getRealConnection().prepareCall(sql, resultSetType,
                        resultSetConcurrency, resultSetHoldability), sql);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public final Savepoint setSavepoint()
		throws SQLException
	{
		try {
			control.checkSavepoint();
			return getRealConnection().setSavepoint();
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public final Savepoint setSavepoint(String name)
		throws SQLException
	{
		try {
			control.checkSavepoint();
			return getRealConnection().setSavepoint(name);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public final void rollback(Savepoint savepoint)
		throws SQLException
	{
		try {
			control.checkRollback();
			getRealConnection().rollback(savepoint);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public final void releaseSavepoint(Savepoint savepoint)
		throws SQLException
	{
		try {
			getRealConnection().releaseSavepoint(savepoint);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}


	public final void setHoldability(int holdability)
		throws SQLException
	{
		try {
			holdability = control.checkHoldCursors(holdability, false);
			getRealConnection().setHoldability(holdability);
			stateHoldability = holdability;
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public final PreparedStatement prepareStatement(
			String sql,
			int autoGeneratedKeys)
    throws SQLException
	{
		try {
			return control.wrapStatement(getRealConnection().prepareStatement(sql, autoGeneratedKeys), sql, autoGeneratedKeys);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public final PreparedStatement prepareStatement(
			String sql,
			int[] columnIndexes)
    throws SQLException
	{
		try {
			return control.wrapStatement(getRealConnection().prepareStatement(sql, columnIndexes), sql, columnIndexes);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public final PreparedStatement prepareStatement(
			String sql,
			String[] columnNames)
    throws SQLException
	{
		try {
			return control.wrapStatement(getRealConnection().prepareStatement(sql, columnNames), sql, columnNames);
		}
		catch (SQLException se)
		{
			notifyException(se);
			throw se;
		}
	}

	public BrokeredPreparedStatement newBrokeredStatement(BrokeredStatementControl statementControl, String sql, Object generatedKeys) throws SQLException {
		return new BrokeredPreparedStatement30(statementControl, sql, generatedKeys);
	}
	public BrokeredCallableStatement newBrokeredStatement(BrokeredStatementControl statementControl, String sql) throws SQLException {
		return new BrokeredCallableStatement30(statementControl, sql);
	}


}
