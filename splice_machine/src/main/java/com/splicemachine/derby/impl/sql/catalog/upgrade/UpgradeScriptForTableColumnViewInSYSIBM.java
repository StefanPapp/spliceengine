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
package com.splicemachine.derby.impl.sql.catalog.upgrade;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.store.access.TransactionController;
import com.splicemachine.derby.impl.sql.catalog.SpliceDataDictionary;
import com.splicemachine.utils.SpliceLogUtils;

/**
 * Created by yxia on 12/17/19.
 */
public class UpgradeScriptForTableColumnViewInSYSIBM extends UpgradeScriptBase {
    public UpgradeScriptForTableColumnViewInSYSIBM(SpliceDataDictionary sdd, TransactionController tc) {
        super(sdd, tc);
    }

    @Override
    protected void upgradeSystemTables() throws StandardException {
        sdd.createTableColumnViewInSysIBM(tc);

        // we need to re-generate the metadataSPS if it is a change of definition of the table/column views in sysIBM
        sdd.updateMetadataSPSes(tc);

        SpliceLogUtils.info(LOG, "Catalog upgraded: added systables and syscolumns view in SYSIBM schema");

    }
}

