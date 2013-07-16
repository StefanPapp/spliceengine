package com.splicemachine.storage;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.BitSet;

/**
 * @author Scott Fines
 * Created on: 7/8/13
 */
public class NullPredicate implements Predicate{
    private static final long serialVersionUID = 3l;
    private boolean filterIfMissing; //when true, equivalent to filterIfMissing null
    private boolean isNullNumericalComparision;
    private int column;

    /**
     * Used for Serialization, DO NOT USE
     */
    @Deprecated
    public NullPredicate() { }

    public NullPredicate(boolean filterIfMissing, boolean isNullNumericalComparison,int column) {
        this.filterIfMissing = filterIfMissing;
        this.isNullNumericalComparision = isNullNumericalComparison;
        this.column = column;
    }

    @Override
    public boolean match(int column,byte[] data, int offset, int length) {
        if(this.column!=column) return true; //not the right column, don't worry about it
        if(isNullNumericalComparision){
            return false; //a numerical comparison with null will never match any columns
        }
        if(filterIfMissing){
            //make sure data is NOT null---data cannot be null, and length >0
            return data!=null && length>0;
        }else{
            //make sure data is null--either data itself is null, or length==0
            return data==null ||length==0;
        }
    }

    @Override
    public boolean checkAfter() {
        /*
         * We have to also check null predicates AFTER the entire row is
         * dealt with. Otherwise, columns that are actually null may never be
         * checked (since they won't necessarily show up in any of the incremental
         * checks).
         */
        return true;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeBoolean(filterIfMissing);
        out.writeBoolean(isNullNumericalComparision);
        out.writeInt(column);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        filterIfMissing = in.readBoolean();
        isNullNumericalComparision = in.readBoolean();
        column = in.readInt();
    }

    @Override
    public void setCheckedColumns(BitSet checkedColumns) {
        checkedColumns.set(column);
    }
}
