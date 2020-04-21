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
 * All such Splice Machine modifications are Copyright 2012 - 2020 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.db.impl.jdbc;

import java.io.Reader;
import java.sql.RowId;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;

import com.splicemachine.db.iapi.reference.SQLState;

public class EmbedPreparedStatement40 extends  EmbedPreparedStatement30 {
    
    public EmbedPreparedStatement40(EmbedConnection conn, String sql, boolean forMetaData,
        int resultSetType, int resultSetConcurrency, int resultSetHoldability,
        int autoGeneratedKeys, int[] columnIndexes, String[] columnNames) throws SQLException {
        super(conn, sql, forMetaData, resultSetType, resultSetConcurrency, resultSetHoldability,
            autoGeneratedKeys, columnIndexes, columnNames);
    }
    
    public void setRowId(int parameterIndex, RowId x) throws SQLException{
        checkStatus();
        try {
            /* JDBC is one-based, DBMS is zero-based */
            getParms().getParameterForSet(parameterIndex - 1).setValue(x);

        } catch (Throwable t) {
            throw EmbedResultSet.noStateChangeException(t);
        }
    }
    
    public void setNString(int index, String value) throws SQLException{
        throw Util.notImplemented();
    }

    public void setNCharacterStream(int parameterIndex, Reader value)
            throws SQLException {
        throw Util.notImplemented();
    }

    public void setNCharacterStream(int index, Reader value, long length) throws SQLException{
        throw Util.notImplemented();
    }

    public void setNClob(int parameterIndex, Reader reader)
            throws SQLException {
        throw Util.notImplemented();
    }

    public void setNClob(int index, NClob value) throws SQLException{
        throw Util.notImplemented();
    }    

    public void setNClob(int parameterIndex, Reader reader, long length)
    throws SQLException{
        throw Util.notImplemented();
    }
    
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException{
        throw Util.notImplemented();
    }
    
   /**
    * JDBC 4.0
    *
    * Retrieves the number, types and properties of this PreparedStatement
    * object's parameters.
    *
    * @return a ParameterMetaData object that contains information about the
    * number, types and properties of this PreparedStatement object's parameters.
    * @exception SQLException if a database access error occurs
    *
    */
    public ParameterMetaData getParameterMetaData()
        throws SQLException
    {
      checkStatus();
      return new EmbedParameterMetaData40(
                getParms(), preparedStatement.getParameterTypes());
    }
    
    /**
     * Returns false unless <code>interfaces</code> is implemented 
     * 
     * @param  interfaces             a Class defining an interface.
     * @return true                   if this implements the interface or 
     *                                directly or indirectly wraps an object 
     *                                that does.
     * @throws java.sql.SQLException  if an error occurs while determining 
     *                                whether this is a wrapper for an object 
     *                                with the given interface.
     */
    public boolean isWrapperFor(Class<?> interfaces) throws SQLException {
        checkStatus();
        return interfaces.isInstance(this);
    }
    
    /**
     * Returns <code>this</code> if this class implements the interface
     *
     * @param  interfaces a Class defining an interface
     * @return an object that implements the interface
     * @throws java.sql.SQLExption if no object if found that implements the 
     * interface
     */
    public <T> T unwrap(java.lang.Class<T> interfaces) 
                            throws SQLException{
        checkStatus();
        try {
            return interfaces.cast(this);
        } catch (ClassCastException cce) {
            throw newSQLException(SQLState.UNABLE_TO_UNWRAP,interfaces);
        }
    }
}    

