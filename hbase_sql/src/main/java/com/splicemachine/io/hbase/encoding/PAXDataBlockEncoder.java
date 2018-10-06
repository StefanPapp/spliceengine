package com.splicemachine.io.hbase.encoding;

import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.hbase.MemstoreAwareObserver;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.encoding.*;
import org.apache.hadoop.hbase.io.hfile.BlockType;
import org.apache.hadoop.hbase.io.hfile.HFileContext;
import org.apache.log4j.Logger;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 *
 * Data Block Encoder that writes both an Adaptive Radix Tree and an ORC Buffer (File, Stripe) and
 * can read via the EncodedSeeker.
 *
 * The PAXEncodingState class does most of the low level details.
 *
 * @see DataBlockEncoder
 * @see PAXEncodingState
 *
 *
 */
public class PAXDataBlockEncoder implements DataBlockEncoder {
    private static Logger LOG = Logger.getLogger(PAXDataBlockEncoder.class);

    public PAXDataBlockEncoder() {
    }

    public PAXDataBlockEncoder(ExecRow execRow) {
        MemstoreAwareObserver.conglomerateThreadLocal.set(execRow);
    }

    @Override
    public void startBlockEncoding(HFileBlockEncodingContext encodingCtx, DataOutputStream out) throws IOException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"startBlockEncoding encodingCtx=%s",encodingCtx);
        encodingCtx.setEncodingState(new PAXEncodingState(out));
        ((HFileBlockDefaultEncodingContext) encodingCtx).prepareEncoding(out);
    }

    @Override
    public int encode(Cell cell, HFileBlockEncodingContext encodingCtx, DataOutputStream out) throws IOException {
        PAXEncodingState paxState = (PAXEncodingState) encodingCtx.getEncodingState();
        return paxState.encode(cell);
    }

    @Override
    public void endBlockEncoding(HFileBlockEncodingContext encodingCtx, DataOutputStream out, byte[] uncompressedBytesWithHeader) throws IOException {
        if (LOG.isDebugEnabled())
            SpliceLogUtils.debug(LOG,"endBlockEncoding encodingCtx=%s",encodingCtx);
        PAXEncodingState paxState = (PAXEncodingState) encodingCtx.getEncodingState();
        paxState.endBlockEncoding(out,uncompressedBytesWithHeader);
        encodingCtx.postEncoding(BlockType.ENCODED_DATA);
    }

    @Override
    public EncodedSeeker createSeeker(KeyValue.KVComparator comparator, HFileBlockDecodingContext decodingCtx) {
        if (LOG.isTraceEnabled())
            SpliceLogUtils.trace(LOG,"createSeeker decodingCtx=%s",decodingCtx);
        return new PAXEncodedSeeker(decodingCtx.getHFileContext().isIncludesMvcc());
    }

    @Override
    public ByteBuffer decodeKeyValues(DataInputStream source, HFileBlockDecodingContext decodingCtx) throws IOException {
        throw new UnsupportedOperationException("Not Supported");
    }

    @Override
    public ByteBuffer getFirstKeyInBlock(ByteBuffer block) {
        PAXEncodedSeeker paxEncodedSeeker = new PAXEncodedSeeker(true);
        paxEncodedSeeker.setCurrentBuffer(block);
        return paxEncodedSeeker.getValueShallowCopy();
    }


    @Override
    public HFileBlockEncodingContext newDataBlockEncodingContext(
            DataBlockEncoding encoding, byte[] header, HFileContext meta) {
        return new HFileBlockDefaultEncodingContext(encoding, header, meta);
    }

    @Override
    public HFileBlockDecodingContext newDataBlockDecodingContext(HFileContext meta) {
        return new HFileBlockDefaultDecodingContext(meta);
    }
}