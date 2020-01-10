/*
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
 * Splice Machine, Inc. has modified this file.
 *
 * All Splice Machine modifications are Copyright 2012 - 2020 Splice Machine, Inc.,
 * and are licensed to you under the License; you may not use this file except in
 * compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 */
package com.splicemachine.db.client.am;

import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.sql.SQLException;

import com.splicemachine.db.client.ClientPooledConnection;
import com.splicemachine.db.client.am.stmtcache.JDBCStatementCache;

import com.splicemachine.db.shared.common.sanity.SanityManager;

/**
 * A logical connection used in a connection pool with capabilities for
 * caching prepared statements.
 *
 * @see CachingLogicalConnection
 */
public class CachingLogicalConnection40
    extends LogicalConnection40 {

    /** JDBC statement cache interactor used to prepare statements and calls. */
    private StatementCacheInteractor cacheInteractor;

    /**
     * Creates a new logical connection which caches prepared statements.
     *
     * @param physicalConnection underlying physical database connection
     * @param pooledConnection associated pooled connection
     * @param stmtCache associated statement cache
     *
     * @throws SqlException if creating the logical connection fails
     */
    public CachingLogicalConnection40(Connection physicalConnection,
                                      ClientPooledConnection pooledConnection,
                                      JDBCStatementCache stmtCache)
            throws SqlException {
        super(physicalConnection, pooledConnection);
        this.cacheInteractor =
                new StatementCacheInteractor(stmtCache, physicalConnection);
    }

    public synchronized void close()
            throws SQLException {
        if (this.cacheInteractor != null) {
            this.cacheInteractor.closeOpenLogicalStatements();
            // Nullify reference to cache interactor to allow it to be GC'ed.
            // It should not be used again, the logical connection is closed.
            this.cacheInteractor = null;
            super.close();
        }
    }

    public synchronized PreparedStatement prepareStatement(String sql)
            throws SQLException {
        checkForNullPhysicalConnection();
        return cacheInteractor.prepareStatement(sql);
    }

    public synchronized PreparedStatement prepareStatement(
                                                String sql,
                                                int resultSetType,
                                                int resultSetConcurrency)
            throws SQLException {
        checkForNullPhysicalConnection();
        return cacheInteractor.prepareStatement(
                    sql, resultSetType, resultSetConcurrency);
    }

    public synchronized PreparedStatement prepareStatement(
                                                String sql,
                                                int resultSetType,
                                                int resultSetConcurrency,
                                                int resultSetHoldability)
            throws SQLException {
        checkForNullPhysicalConnection();
        return cacheInteractor.prepareStatement(
                sql, resultSetType,resultSetConcurrency, resultSetHoldability);
    }

    public synchronized PreparedStatement prepareStatement(
                                                String sql,
                                                int autoGeneratedKeys)
            throws SQLException {
        checkForNullPhysicalConnection();
        return cacheInteractor.prepareStatement(sql, autoGeneratedKeys);
    }

    public synchronized PreparedStatement prepareStatement(
                                                String sql,
                                                int[] columnIndexes)
            throws SQLException {
        checkForNullPhysicalConnection();
        PreparedStatement ps = null;
        if (columnIndexes != null && columnIndexes.length > 1) {
            // This should probably be extended to use a separate type of
            // statement key (instead of just saying its a statement which
            // returns auto-generated keys), to force it throught the driver
            // validation code.
            // For now, disable statement pooling and fail in sane builds only,
            // or in the relevant parts of the driver if still not supported.
            ps = super.prepareStatement(sql, columnIndexes);
            // If we get this far, the rest of the driver has extended
            // its capabilities and this class is lagging behind...
            if (SanityManager.DEBUG) {
                SanityManager.THROWASSERT("CachingLogicalConnection is " +
                        "missing the capability to handle prepareStatement " +
                        "with an int array with more than one elemenet.");
            }
            // No caching being done, but we are able to continue.
        } else {
            int generatedKeys = Statement.RETURN_GENERATED_KEYS;
            // If indexes is null or empty, don't return autogenerated keys.
            if (columnIndexes == null || columnIndexes.length == 0) {
                generatedKeys = Statement.NO_GENERATED_KEYS;
            }
            ps = cacheInteractor.prepareStatement(sql, generatedKeys);
        }
        return ps;
    }

    public synchronized PreparedStatement prepareStatement(
                                                String sql,
                                                String[] columnNames)
            throws SQLException {
        checkForNullPhysicalConnection();
        PreparedStatement ps = null;
        if (columnNames != null && columnNames.length > 1) {
            // This should probably be extended to use a separate type of
            // statement key (instead of just saying its a statement which
            // returns auto-generated keys), to force it throught the driver
            // validation code.
            // For now, disable statement pooling and fail in sane builds only,
            // or in the relevant parts of the driver if still not supported.
            ps = super.prepareStatement(sql, columnNames);
            // If we get this far, the rest of the driver has extended
            // its capabilities and this class is lagging behind...
            if (SanityManager.DEBUG) {
                SanityManager.THROWASSERT("CachingLogicalConnection is " +
                        "missing the capability to handle prepareStatement " +
                        "with a string array with more than one elemenet.");
            }
            // No caching being done, but we are able to continue.
        } else {
            int generatedKeys = Statement.RETURN_GENERATED_KEYS;
            // If names is null or empty, don't return autogenerated keys.
            if (columnNames == null || columnNames.length == 0) {
                generatedKeys = Statement.NO_GENERATED_KEYS;
            }
            ps = cacheInteractor.prepareStatement(sql, generatedKeys);
        }
        return ps;
    }

    public synchronized CallableStatement prepareCall(String sql)
            throws SQLException {
        checkForNullPhysicalConnection();
        return cacheInteractor.prepareCall(sql);
    }

    public synchronized CallableStatement prepareCall(String sql,
                                                      int resultSetType,
                                                      int resultSetConcurrency)
            throws SQLException {
        checkForNullPhysicalConnection();
        return cacheInteractor.prepareCall(
                sql, resultSetType, resultSetConcurrency);
    }

    public synchronized CallableStatement prepareCall(String sql,
                                                      int resultSetType,
                                                      int resultSetConcurrency,
                                                      int resultSetHoldability)
            throws SQLException {
        checkForNullPhysicalConnection();
        return cacheInteractor.prepareCall(
                sql, resultSetType, resultSetConcurrency, resultSetHoldability);
    }
}
