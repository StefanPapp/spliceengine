package com.splicemachine.perf.runner.qualifiers;

import com.google.common.base.Preconditions;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Random;

/**
 * @author Scott Fines
 *         Created on: 3/15/13
 */
public class IntRange implements Qualifier{
    private final int start;
    private final int stop;

    private final Random random = new Random();

    public IntRange(int start, int stop) {
        this.start = start;
        this.stop = stop;
    }

    @Override
    public void setInto(PreparedStatement ps, int position) throws SQLException {
        ps.setInt(position,nextValue());
    }

    @Override
    public void validate(ResultSet rs, int position) throws SQLException {
        int value = rs.getInt(position);
        Preconditions.checkArgument(value >= start,value+" < "+ start);
        Preconditions.checkArgument(value < stop, value +" >= "+ stop);
    }

    private int nextValue() {
        int val = random.nextInt(stop);

        /*
         * val is in range [0,stop). We need to transform it to fit within the range
         * [start,stop).
         */
        return ((stop-start)/stop)*val+start;
    }

    public static Qualifier create(Map<String, Object> qualifierConfig) {
        int start =((Double)qualifierConfig.get("start")).intValue();
        int stop = ((Double)qualifierConfig.get("stop")).intValue();
        return new IntRange(start,stop);
    }

    @Override
    public String toString() {
        return "IntRange{["+start+","+stop+")}";
    }
}
