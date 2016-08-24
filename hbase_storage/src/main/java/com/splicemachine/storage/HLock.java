/*
 * Copyright 2012 - 2016 Splice Machine, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.splicemachine.storage;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.hadoop.hbase.regionserver.HRegion;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * @author Scott Fines
 *         Date: 12/15/15
 */
public class HLock implements Lock{
    protected static boolean HAS_READ_LOCK_IMPL = false;

    static {
        try {
            Class rowLockContextClass = Class.forName("org.apache.hadoop.hbase.regionserver.HRegion$RowLockContext");
            rowLockContextClass.getDeclaredMethod("newReadLock", new Class[]{});
            HAS_READ_LOCK_IMPL = true;
        } catch (Exception e) {

        }
    }


    private final byte[] key;

    private HRegion.RowLock delegate;
    private HRegion region;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",justification = "Intentional")
    public HLock(HRegion region,byte[] key){
        this.key = key;
        this.region = region;
    }

    @Override public void lock(){
        throw new UnsupportedOperationException("Lock Called");
    }

    @Override
    public void lockInterruptibly() throws InterruptedException{
        lock();
    }

    /**
     *
     * Try Lock Implementation
     *
     * // HBase as part of its 1.1.x release modified the locks to
     * // throw IOException when it cannot be acquired vs. returning null
     *
     * @return
     */
    @Override
    public boolean tryLock(){
        try{
            delegate = region.getRowLock(key,HAS_READ_LOCK_IMPL?true:false); // Null Lock Delegate means not run...
            return delegate!=null;
        }catch(IOException e){
            return false;
        }
    }

    /**
     *
     * The TimeUnit passed in is not used for HBase Row Locks
     *
     * @param time
     * @param unit
     * @return
     * @throws InterruptedException
     */
    @Override
    public boolean tryLock(long time,@Nonnull TimeUnit unit) throws InterruptedException{
        return tryLock();
    }

    @Override
    public void unlock(){
        if(delegate!=null) delegate.release();
    }

    @Override
    public @Nonnull Condition newCondition(){
        throw new UnsupportedOperationException("Cannot support conditions on an HLock");
    }
}
