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

package com.splicemachine.db.impl.sql.compile;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.iapi.sql.compile.CompilerContext;
import com.splicemachine.db.iapi.sql.compile.OptimizablePredicate;
import com.splicemachine.db.iapi.types.*;
import com.splicemachine.system.SimpleSparkVersion;
import com.splicemachine.system.SparkVersion;
import org.apache.commons.lang3.mutable.MutableInt;

import static com.splicemachine.db.iapi.reference.Property.SPLICE_SPARK_COMPILE_VERSION;
import static com.splicemachine.db.iapi.reference.Property.SPLICE_SPARK_VERSION;
import static com.splicemachine.db.iapi.services.io.StoredFormatIds.*;
import static java.lang.String.format;

/**
 * Utility to get the string representation of a given operator.
 * <p/>
 * Used for debugging.
 */
public class OperatorToString {

    public boolean      sparkExpression;
    public SparkVersion sparkVersion;
    public MutableInt   relationalOpDepth;

    private static final SparkVersion spark_2_3_0 = new SimpleSparkVersion("2.3.0");

    OperatorToString(boolean    sparkExpression,
                     MutableInt relationalOpDepth) {
        this.sparkExpression   = sparkExpression;
        this.sparkVersion      = getSparkVersion();
        this.relationalOpDepth = relationalOpDepth;
    }

    /**
     * Satisfy non-guava (derby client) compile dependency.
     * @param predicate the predicate
     * @return Return string representation of Derby Predicate
     */
    public static String toString(Predicate predicate) {
        if (predicate == null) {
            return null;
        }
        ValueNode operand = predicate.getAndNode().getLeftOperand();
        return opToString(operand);
    }

    // Helper method for initializing the spark major version.
    private static SparkVersion getSparkVersion() {

        // If splice.spark.version is manually set, use it...
        String spliceSparkVersionString = System.getProperty(SPLICE_SPARK_VERSION);
        SparkVersion sparkVersion =
            (spliceSparkVersionString != null && !spliceSparkVersionString.isEmpty()) ?
              new SimpleSparkVersion(spliceSparkVersionString) : null;

        // ... otherwise pick up the splice compile-time version of spark.
        if (sparkVersion == null || sparkVersion.isUnknown()) {
            spliceSparkVersionString = System.getProperty(SPLICE_SPARK_COMPILE_VERSION);
            sparkVersion = new SimpleSparkVersion(spliceSparkVersionString);
            if (sparkVersion.isUnknown())
                sparkVersion = CompilerContext.DEFAULT_SPLICE_SPARK_VERSION;
        }
        return sparkVersion;
    }

    /**
     * Satisfy non-guava (derby client) compile dependency.
     * @param predicateList the predicate list
     * @return Return string representation of Derby Predicates in a predicate list
     */
    public static String toString(PredicateList predicateList) {
        if (predicateList == null || predicateList.isEmpty()) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0, s = predicateList.size(); i < s; i++) {
            OptimizablePredicate predicate = predicateList.getOptPredicate(i);
            ValueNode operand = ((Predicate)predicate).getAndNode().getLeftOperand();
            buf.append(opToString(operand)).append(", ");
        }
        if (buf.length() > 2) {
            // trim last ", "
            buf.setLength(buf.length() - 2);
        }
        return buf.toString();
    }

    /**
     * Return string representation of a Derby expression
     */
    public static String opToString(ValueNode operand) {
        try {
            OperatorToString vars =
                new OperatorToString(false,
                                     new MutableInt(0));
            return opToString2(operand, vars);
        }
        catch(StandardException e) {
            return "Bad SQL Expression";
        }
    }

    /**
     * Return a spark SQL expression given a Derby SQL expression, with column
     * references indicating column names in the source DataFrame.
     */
    public static String opToSparkString(ValueNode operand) throws StandardException {
        String retval = "";

        // Do not throw any errors encountered.  An error condition
        // just means we can't generate a valid spark representation
        // of the SQL expression to apply to a native spark Dataset,
        // so should not be considered a fatal error.
        try {
            OperatorToString vars =
                new OperatorToString(true,
                                     new MutableInt(0));
            retval = opToString2(operand, vars);

        }
        catch (StandardException e) {
            if (e.getSQLState() != SQLState.LANG_DOES_NOT_IMPLEMENT)
                throw e;
        }
        return retval;
    }

    private static void throwNotImplementedError() throws StandardException {
        throw StandardException.newException(SQLState.LANG_DOES_NOT_IMPLEMENT);
    }

    // We don't support REAL (float), because the way spark
    // evaluates expressions involving float causes accuracy errors
    // that don't occur when splice does the evaluation.
    private static boolean sparkSupportedType(int typeFormatId) {
        return (typeFormatId == BOOLEAN_TYPE_ID  ||
                typeFormatId == DATE_TYPE_ID     ||
                typeFormatId == CHAR_TYPE_ID     ||
                typeFormatId == VARCHAR_TYPE_ID  ||
                typeFormatId == LONGVARCHAR_TYPE_ID  ||
                typeFormatId == TINYINT_TYPE_ID  ||
                typeFormatId == SMALLINT_TYPE_ID ||
                typeFormatId == INT_TYPE_ID      ||
                typeFormatId == LONGINT_TYPE_ID  ||
                typeFormatId == DECIMAL_TYPE_ID  ||
                typeFormatId == DOUBLE_TYPE_ID   ||
                typeFormatId == TIMESTAMP_TYPE_ID);
    }

    private static boolean isNumericTypeFormatID(int typeFormatId) {
        return (typeFormatId == TINYINT_TYPE_ID  ||
                typeFormatId == SMALLINT_TYPE_ID ||
                typeFormatId == INT_TYPE_ID      ||
                typeFormatId == LONGINT_TYPE_ID  ||
                typeFormatId == DECIMAL_TYPE_ID  ||
                typeFormatId == DOUBLE_TYPE_ID   ||
                typeFormatId == REAL_TYPE_ID);
    }

    private static boolean isOverflowSensitive(ValueNode operand) throws StandardException {
        return (operand.getTypeId().getTypeFormatId() == LONGINT_TYPE_ID ||
                operand.getTypeId().getTypeFormatId() == DECIMAL_TYPE_ID ||
                operand.getTypeId().getTypeFormatId() == DOUBLE_TYPE_ID);
    }
    private static void checkOverflowHidingCases(BinaryArithmeticOperatorNode bao,
                                                 MutableInt relationalOpDepth) throws StandardException {
        if (relationalOpDepth.intValue() <= 0)
            return;
        ValueNode leftOperand = bao.getLeftOperand();
        ValueNode rightOperand = bao.getRightOperand();
        if (isOverflowSensitive(leftOperand) || isOverflowSensitive(rightOperand))
            throwNotImplementedError();
    }

    /**
     * Returns The string representation of a Derby expression tree.
     * 
     * @param  operand           The expression tree to parse and translate to text.
     * @param  vars.sparkExpression
     *                           True, if converting the expression to spark SQL,
     *                           otherwise false.
     * @param  vars.sparkVersion
     *                           The spark major version for which we're generating
     *                           the spark SQL expression, if "sparkExpression" is true.
     * @param  vars.relationalOpDepth
     *                           The current number of relational operators or other
     *                           null-hiding expressions, such as CASE, which we
     *                           are nested within.  Used in determining if a spark
     *                           SQL expression is ANSI SQL compliant.
     * @return true              The SQL representation of the expression tree
     *                           held in "operand".
     * @throws StandardException  
     * @notes  If sparkExpression is true, and the expression tree cannot be
     *         represented in Spark SQL, or the resulting expression would not
     *         behave in an ANSI SQL compliant manner (e.g. would hide numeric
     *         overflows), then a zero length String is returned.
     */
    private static String opToString2(ValueNode        operand,
                                      OperatorToString vars) throws StandardException {
        if(operand==null){
            return "";
        } else if (operand instanceof UntypedNullConstantNode)
            return " null ";
        else if(operand instanceof UnaryOperatorNode){
            UnaryOperatorNode uop=(UnaryOperatorNode)operand;
            String operatorString = uop.getOperatorString();
            if (vars.sparkExpression) {
                if (operand instanceof IsNullNode) {
                    vars.relationalOpDepth.increment();
                    String isNullString = format("%s %s", opToString2(uop.getOperand(), vars), operatorString);
                    vars.relationalOpDepth.decrement();
                    return isNullString;
                }
                else if (operand instanceof ExtractOperatorNode) {
                    ExtractOperatorNode eon = (ExtractOperatorNode) operand;
                    String functionName = eon.sparkFunctionName();

                    // Splice extracts fractional seconds, but spark only extracts whole seconds.
                    if (functionName.equals("SECOND") || functionName.equals("WEEK") ||
                        functionName.equals("WEEKDAY") || functionName.equals("WEEKDAYNAME"))
                        throwNotImplementedError();
                    else
                        return format("%s(%s) ", functionName, opToString2(uop.getOperand(), vars));
                }
                else if (operand instanceof DB2LengthOperatorNode) {
                    DB2LengthOperatorNode lengthOp = (DB2LengthOperatorNode)operand;
                    String functionName = lengthOp.getOperatorString();
                    ValueNode vn = lengthOp.getOperand();
                    int type = vn.getTypeId().getTypeFormatId();
                    boolean stringType =
                             (type == CHAR_TYPE_ID ||
                              type == VARCHAR_TYPE_ID ||
                              type == LONGVARCHAR_TYPE_ID ||
                              type == CLOB_TYPE_ID);
                    // The length function has the same behavior on splice and
                    // spark only for string types.
                    if (!stringType)
                        throwNotImplementedError();

                    return format("%s(%s) ", functionName, opToString2(lengthOp.getOperand(), vars));
                }
                else if (operand instanceof UnaryArithmeticOperatorNode) {
                    UnaryArithmeticOperatorNode uao = (UnaryArithmeticOperatorNode) operand;
                    if (operatorString.equals("+") || operatorString.equals("-"))
                        return format("%s%s ", operatorString, opToString2(uao.getOperand(), vars));
                    else if (operatorString.equals("ABS/ABSVAL"))
                        operatorString = "abs";
                }
                else if (operand instanceof SimpleStringOperatorNode) {
                    SimpleStringOperatorNode sso = (SimpleStringOperatorNode) operand;
                    return format("%s(%s) ", operatorString, opToString2(sso.getOperand(), vars));
                }
                else
                    throwNotImplementedError();
            }
            return format("%s(%s)", operatorString, opToString2(uop.getOperand(), vars));
        }else if(operand instanceof BinaryRelationalOperatorNode){
            vars.relationalOpDepth.increment();
            BinaryRelationalOperatorNode bron=(BinaryRelationalOperatorNode)operand;
            try {
                InListOperatorNode inListOp = bron.getInListOp();
                if (inListOp != null) return opToString2(inListOp, vars);
    
                String opString =
                        format("(%s %s %s)", opToString2(bron.getLeftOperand(), vars),
                               bron.getOperatorString(), opToString2(bron.getRightOperand(), vars));
                vars.relationalOpDepth.decrement();
                return opString;
            }
            catch (StandardException e) {
                if (vars.sparkExpression)
                    throw e;
                else
                    return "PARSE_ERROR_WHILE_CONVERTING_OPERATOR";
            }
        }else if(operand instanceof BinaryListOperatorNode){
            vars.relationalOpDepth.increment();
            BinaryListOperatorNode blon = (BinaryListOperatorNode)operand;
            StringBuilder inList = new StringBuilder("(");
            if (!blon.isSingleLeftOperand()) {
                ValueNodeList vnl = blon.leftOperandList;
                inList.append("(");
                for (int i = 0; i < vnl.size(); i++) {
                    ValueNode vn = (ValueNode) vnl.elementAt(i);
                    if (i != 0)
                        inList.append(",");
                    inList.append(opToString2(vn, vars));
                }
                inList.append(")");
            }
            else
                inList.append(opToString2(blon.getLeftOperand(), vars));
            inList.append(" ").append(blon.getOperator()).append(" (");
            ValueNodeList rightOperandList=blon.getRightOperandList();
            boolean isFirst = true;
            for(Object qtn: rightOperandList){
                if(isFirst) isFirst = false;
                else inList = inList.append(",");
                inList = inList.append(opToString2((ValueNode)qtn, vars));
            }
            String retval = inList.append("))").toString();
            vars.relationalOpDepth.decrement();
            return retval;
        }else if (operand instanceof BinaryOperatorNode) {
            BinaryOperatorNode bop = (BinaryOperatorNode) operand;
            ValueNode leftOperand = bop.getLeftOperand();
            ValueNode rightOperand = bop.getRightOperand();
            String leftOperandString = opToString2(leftOperand, vars);
            String rightOperandString = opToString2(rightOperand, vars);

            if (vars.sparkExpression) {
                if (operand instanceof ConcatenationOperatorNode)
                    return format("concat(%s, %s) ", opToString2(leftOperand, vars),
                                                    opToString2(rightOperand, vars));
                else if (operand instanceof TruncateOperatorNode) {
                    if (leftOperand.getTypeId().getTypeFormatId() == DATE_TYPE_ID) {
                        return format("trunc(%s, %s) ", opToString2(leftOperand, vars),
                                                       opToString2(rightOperand, vars));
                    }
                    else if (vars.sparkVersion.greaterThanOrEqualTo(spark_2_3_0) &&
                               leftOperand.getTypeId().getTypeFormatId() == TIMESTAMP_TYPE_ID) {
                        return format("date_trunc(%s, %s) ", opToString2(rightOperand, vars),
                                                            opToString2(leftOperand, vars));
                    } else
                        throwNotImplementedError();
                }
                else if (operand instanceof BinaryArithmeticOperatorNode) {
                    BinaryArithmeticOperatorNode bao = (BinaryArithmeticOperatorNode)operand;

                    // The way spark converts real to double causes
                    // inaccurate results, so avoid native spark data sets
                    // for these cases.
                    if (leftOperand.getTypeId().getTypeFormatId() == REAL_TYPE_ID ||
                        rightOperand.getTypeId().getTypeFormatId() == REAL_TYPE_ID)
                        throwNotImplementedError();

                    // Spark may hide overflow errors by generating +Infinity, -Infinity
                    // or null, and any predicate containing these values may appear to
                    // evaluate successfully, but really the query should error out.
                    checkOverflowHidingCases(bao, vars.relationalOpDepth);

                    // Splice automatically builds a binary arithmetic expression
                    // in the requested final data type.  For spark, we need to
                    // provide an explicit CAST to get the same effect.
                    boolean doCast = false;
                    String targetType = null;

                    if (leftOperand.getTypeId().getTypeFormatId() !=
                        bao.getTypeId().getTypeFormatId() &&
                        rightOperand.getTypeId().getTypeFormatId() !=
                        bao.getTypeId().getTypeFormatId()) {
                        doCast = true;
                        targetType = bao.getTypeServices().toSparkString();
                    }
                    if (doCast) {
                        if (leftOperand.getTypeServices().getTypeId().typePrecedence() >
                            rightOperand.getTypeServices().getTypeId().typePrecedence())
                            leftOperandString = format("CAST(%s as %s) ",
                                                        leftOperandString,
                                                        targetType);
                        else
                            rightOperandString = format("CAST(%s as %s) ",
                                                         rightOperandString,
                                                         targetType);
                    }

                    // Though documented as supported by spark, mod
                    // is not recognized.  Disable for now.
                    // Division by zero results in a null value on
                    // spark, but splice expects this to error out,
                    // so we can't use native spark sql for "/".
                    if (bao.getOperatorString() == "mod" ||
                        bao.getOperatorString() == "/")
                        throwNotImplementedError();
                    else if (bao.getOperatorString() == "+") {
                        if (leftOperand.getTypeId().getTypeFormatId() == DATE_TYPE_ID)
                            return format("date_add(%s, %s) ", opToString2(leftOperand, vars),
                            opToString2(rightOperand, vars));
                    }
                    else if (bao.getOperatorString() == "-") {
                        if (leftOperand.getTypeId().getTypeFormatId() == DATE_TYPE_ID)
                            return format("date_sub(%s, %s) ", opToString2(leftOperand, vars),
                            opToString2(rightOperand, vars));
                    }
                }
                else if (operand.getClass() == BinaryOperatorNode.class) {
                    if (((BinaryOperatorNode) operand).isRepeat()) {
                        return format("%s(%s, %s) ", bop.getOperatorString(),
                          opToString2(bop.getLeftOperand(), vars), opToString2(bop.getRightOperand(), vars));
                    }
                }
                else if (operand instanceof TimestampOperatorNode ||
                         operand instanceof SimpleLocaleStringOperatorNode)
                    throwNotImplementedError();
            }

            // Need to CAST if the final type is decimal because the precision
            // or scale used by spark to hold the result may not match what
            // splice has chosen, and could cause an overflow.
            boolean doCast = operand instanceof BinaryArithmeticOperatorNode &&
                             operand.getTypeId().getTypeFormatId() == DECIMAL_TYPE_ID &&
                             vars.sparkExpression;
            String expressionString =
                    format("(%s %s %s)", leftOperandString,
                                         bop.getOperatorString(), rightOperandString);
            if (doCast) {
                // Spark generates a null value on decimal overflow instead of erroring out,
                // (see SPARK-23179), so until an option is provided to catch
                // the overflow, we have to avoid spark-native evaluation of operations
                // which could hide the overflow.
                throwNotImplementedError();
                expressionString = format("CAST(%s as %s) ",
                                           expressionString,
                                           operand.getTypeServices().toSparkString());
            }
            return expressionString;
        } else if (operand instanceof ArrayOperatorNode) {
            if (vars.sparkExpression)
                throwNotImplementedError();
            ArrayOperatorNode array = (ArrayOperatorNode) operand;
            ValueNode op = array.operand;
            return format("%s[%d]", op == null ? "" : opToString2(op, vars), array.extractField);
        } else if (operand instanceof TernaryOperatorNode) {
            TernaryOperatorNode top = (TernaryOperatorNode) operand;
            ValueNode rightOp = top.getRightOperand();
            if (vars.sparkExpression) {
                if (operand instanceof LikeEscapeOperatorNode) {
                    vars.relationalOpDepth.increment();
                    if (rightOp != null)
                        throwNotImplementedError();
                    else {
                        String likeString =  format("(%s %s %s) ", opToString2(top.getReceiver(), vars), top.getOperator(),
                        opToString2(top.getLeftOperand(), vars));
                        vars.relationalOpDepth.decrement();
                        return likeString;
                    }
                }
                else if (operand.getClass() == TernaryOperatorNode.class) {
                    vars.relationalOpDepth.increment();
                    if (top.getOperator().equals("LOCATE") ||
                        top.getOperator().equals("replace") ||
                        top.getOperator().equals("substring") ) {

                        if (vars.sparkVersion.lessThan(spark_2_3_0) && top.getOperator().equals("replace"))
                            throwNotImplementedError();

                        vars.relationalOpDepth.decrement();
                        String retval = format("%s(%s, %s, %s) ", top.getOperator(), opToString2(top.getReceiver(), vars),
                                opToString2(top.getLeftOperand(), vars), opToString2(top.getRightOperand(), vars));
                        vars.relationalOpDepth.decrement();
                        return retval;
                    }
                    else if (top.getOperator().equals("trim")) {
                        // Trim is supported starting at Spark 2.3.
                        if (vars.sparkVersion.lessThan(spark_2_3_0))
                            throwNotImplementedError();
                        String retval;
                        if (top.isLeading())
                            retval = format("%s(LEADING %s FROM %s) ",  top.getOperator(), opToString2(top.getLeftOperand(), vars),
                                opToString2(top.getReceiver(), vars));
                        else if (top.isTrailing())
                            retval = format("%s(TRAILING %s FROM %s) ",  top.getOperator(), opToString2(top.getLeftOperand(), vars),
                                opToString2(top.getReceiver(), vars));
                        else
                            retval = format("%s(BOTH %s FROM %s) ",  top.getOperator(), opToString2(top.getLeftOperand(), vars),
                                opToString2(top.getReceiver(), vars));

                        vars.relationalOpDepth.decrement();
                        return retval;
                    }
                    else
                        throwNotImplementedError();
                }
                else
                    throwNotImplementedError();
            }
            return format("%s(%s, %s%s) ", top.getOperator(), opToString2(top.getReceiver(), vars),
                          opToString2(top.getLeftOperand(), vars), rightOp == null ? "" : ", " + opToString2(rightOp, vars));
        }
        else if (operand instanceof ArrayConstantNode) {
            vars.relationalOpDepth.increment();
            if (vars.sparkExpression)
                throwNotImplementedError();;
            ArrayConstantNode arrayConstantNode = (ArrayConstantNode) operand;
            StringBuilder builder = new StringBuilder();
            builder.append("[");
            int i = 0;
            for (Object object: arrayConstantNode.argumentsList) {
                if (i!=0)
                    builder.append(",");
                builder.append(opToString2((ValueNode)object, vars));
                i++;
            }
            builder.append("]");
            vars.relationalOpDepth.decrement();
            return builder.toString();
        } else if (operand instanceof ListValueNode) {
            vars.relationalOpDepth.increment();
            if (vars.sparkExpression)
                throwNotImplementedError();;
            ListValueNode lcn = (ListValueNode) operand;
            StringBuilder builder = new StringBuilder();
            builder.append("(");
            for (int i = 0; i < lcn.numValues(); i++) {
                ValueNode vn = lcn.getValue(i);
                if (i != 0)
                    builder.append(",");
                builder.append(opToString2(vn, vars));
            }
            builder.append(")");
            vars.relationalOpDepth.decrement();
            return builder.toString();
        }
        else if (operand instanceof ColumnReference) {
            ColumnReference cr = (ColumnReference) operand;
            String table = cr.getTableName();
            ResultColumn source = cr.getSource();
            if (! vars.sparkExpression) {
                return format("%s%s%s", table == null ? "" : format("%s.", table),
                cr.getColumnName(), source == null ? "" :
                format("[%s:%s]", source.getResultSetNumber(), source.getVirtualColumnId()));
            }
            else {
                if (!sparkSupportedType(cr.getTypeId().getTypeFormatId()))
                    throwNotImplementedError();

                return format("c%d ", source.getVirtualColumnId()-1);
            }
        } else if (operand instanceof VirtualColumnNode) {
            VirtualColumnNode vcn = (VirtualColumnNode) operand;
            ResultColumn source = vcn.getSourceColumn();
            String table = source.getTableName();
            if (! vars.sparkExpression) {
                return format("%s%s%s", table == null ? "" : format("%s.", table),
                source.getName(),
                format("[%s:%s]", source.getResultSetNumber(), source.getVirtualColumnId()));
            }
            else {
                if (!sparkSupportedType(operand.getTypeId().getTypeFormatId()))
                    throwNotImplementedError();

                return format("c%d ", source.getVirtualColumnId()-1);
            }
        } else if (operand instanceof SubqueryNode) {
            if (vars.sparkExpression)
                throwNotImplementedError();
            SubqueryNode subq = (SubqueryNode) operand;
            return format("subq=%s", subq.getResultSet().getResultSetNumber());
        } else if (operand instanceof ConstantNode) {
            ConstantNode cn = (ConstantNode) operand;
            try {
                DataValueDescriptor dvd = cn.getValue();
                String str = null;
                if (dvd == null)
                    str = "null";
                else if (vars.sparkExpression) {
                    if (dvd instanceof SQLChar ||
                        dvd instanceof SQLVarchar ||
                        dvd instanceof SQLLongvarchar ||
                        dvd instanceof SQLClob)
                        str = format("\'%s\' ", cn.getValue().getString());
                    else if (dvd instanceof SQLDate)
                        str = format("date(\'%s\') ", cn.getValue().getString());
                    else if (dvd instanceof SQLTimestamp)
                        str = format("timestamp(\'%s\') ", cn.getValue().getString());
                    else if (dvd instanceof SQLDouble)
                        str = format("double(\'%s\') ", cn.getValue().getString());
                    else if (dvd instanceof SQLInteger  ||
                             dvd instanceof SQLDecimal  ||
                             dvd instanceof SQLBoolean)
                        str = cn.getValue().getString();
                    else if (dvd instanceof SQLLongint  ||
                             dvd instanceof SQLSmallint ||
                             dvd instanceof SQLTinyint)
                        str = format("CAST(%s as %s) ",
                                      cn.getValue().getString(),
                                      cn.getTypeServices().toSparkString());
                    else
                        throwNotImplementedError();
                }
                else
                    str = cn.getValue().getString();
                return str;
            } catch (StandardException se) {
                if (vars.sparkExpression)
                    throw(se);
                else
                    return se.getMessage();
            }
        } else if(operand instanceof CastNode){
            String castString = null;
            if (vars.sparkExpression) {
                StringBuilder sb = new StringBuilder();
                CastNode cn = (CastNode)operand;
                ValueNode castOperand = cn.getCastOperand();
                int typeFormatId = operand.getTypeId().getTypeFormatId();
                if (!sparkSupportedType(typeFormatId))
                    throwNotImplementedError();

                sb.append(format("CAST(%s ", opToString2(castOperand, vars)));
                if (typeFormatId == LONGVARCHAR_TYPE_ID)
                    sb.append("AS varchar(32670)) ");
                else if (isNumericTypeFormatID(typeFormatId)) {
                    // Disallow manual cast to a numeric type.
                    // Decimal overflow on spark returns null
                    // instead of throwing an error.
                    // CASTing to other numerics can truncate
                    // higher order bits and return incorrect
                    // results instead of throwing an overflow
                    // error.
                    throwNotImplementedError();
                }
                else
                    sb.append(format("AS %s) ", cn.getTypeServices().toSparkString()));
                castString = sb.toString();
            }
            else
                castString = opToString2(((CastNode)operand).getCastOperand(), vars);

            return castString;
        }
        else if (operand instanceof CoalesceFunctionNode) {
            vars.relationalOpDepth.increment();
            StringBuilder sb = new StringBuilder();
            sb.append("coalesce(");
            int i = 0;
            for (Object ob : ((CoalesceFunctionNode) operand).argumentsList) {
                ValueNode vn = (ValueNode)ob;
                if (i > 0)
                    sb.append(", ");
                sb.append(format("%s", opToString2(vn, vars)));
                i++;
            }
            sb.append(") ");
            vars.relationalOpDepth.decrement();
            return sb.toString();
        }
        else if (operand instanceof CurrentDatetimeOperatorNode) {
            CurrentDatetimeOperatorNode cdtOp = (CurrentDatetimeOperatorNode)operand;
            StringBuilder sb = new StringBuilder();
            if (cdtOp.isCurrentDate())
                sb.append("current_date");
            else if (cdtOp.isCurrentTime()) {
                if (vars.sparkExpression)
                    throwNotImplementedError();
                sb.append("current_time");
            }
            else if (cdtOp.isCurrentTimestamp())
                sb.append("current_timestamp");
            else
                throwNotImplementedError();
            if (vars.sparkExpression)
                sb.append("() ");

            return sb.toString();
        }
        else if (operand instanceof ConditionalNode) {
            vars.relationalOpDepth.increment();
            ConditionalNode cn = (ConditionalNode)operand;
            StringBuilder sb = new StringBuilder();
            sb.append(format ("CASE WHEN %s ", opToString2(cn.getTestCondition(), vars)));
            int i = 0;
            for (Object ob : cn.getThenElseList()) {
                ValueNode vn = (ValueNode)ob;
                if (i == 0)
                    sb.append(format("THEN %s ", opToString2(vn, vars)));
                else
                    sb.append(format("ELSE %s ", opToString2(vn, vars)));
                i++;
            }
            sb.append("END ");
            vars.relationalOpDepth.decrement();
            return sb.toString();
        }
        else {
            if (vars.sparkExpression) {
                if (operand instanceof JavaToSQLValueNode &&
                ((JavaToSQLValueNode) operand).isSystemFunction()) {
                    vars.relationalOpDepth.increment();
                    JavaToSQLValueNode javaFun = (JavaToSQLValueNode) operand;
                    JavaValueNode method = javaFun.getJavaValueNode();

                    if (method instanceof StaticMethodCallNode) {
                        StaticMethodCallNode smc = (StaticMethodCallNode) method;
                        StringBuilder sb = new StringBuilder();
                        String methodName = smc.getMethodName();
                        boolean needsExtraClosingParens = false;

                        // Spark MONTHS_BETWEEN calculates fractional
                        // months, splice MONTH_BETWEEN does not.
                        // Splice and spark use different rounding rules
                        // for the ROUND function.
                        // ADD_MONTHS returns incorrect results on
                        // Spark for old dates.
                        if (methodName.equals("MONTH_BETWEEN") ||
                            methodName.equals("REGEXP_LIKE")   ||
                            methodName.equals("ADD_MONTHS")    ||
                            methodName.equals("ROUND"))
                            throwNotImplementedError();
                        else if (methodName.equals("toDegrees"))
                            methodName = "degrees";
                        else if (methodName.equals("toRadians"))
                            methodName = "radians";
                        else if (methodName.equals("SIGN")) {
                            methodName = "int(sign";
                            needsExtraClosingParens = true;
                        }
                        else if (methodName.equals("floor")) {
                            methodName = "double(floor";
                            needsExtraClosingParens = true;
                        }
                        else if (methodName.equals("RAND")) {
                            JavaValueNode param = smc.getMethodParms()[0];
                            if (!(param instanceof SQLToJavaValueNode))
                                throwNotImplementedError();
                            if (! (((SQLToJavaValueNode) param).getSQLValueNode() instanceof ConstantNode))
                                throwNotImplementedError();
                        }
                        else if (methodName.equals("random")) {
                            methodName = "rand";
                        }
                        else if (methodName.equals("ceil")) {
                            methodName = "double(ceil";
                            needsExtraClosingParens = true;
                        }
                        sb.append(format("%s(", methodName));
                        int i = 0;
                        for (JavaValueNode param : smc.getMethodParms()) {
                            if (!(param instanceof SQLToJavaValueNode))
                                throwNotImplementedError();
                            ValueNode vn = ((SQLToJavaValueNode) param).getSQLValueNode();
                            if (i > 0)
                                sb.append(", ");
                            sb.append(opToString2(vn, vars));
                            i++;
                        }
                        if (needsExtraClosingParens)
                            sb.append(")");
                        sb.append(") ");
                        vars.relationalOpDepth.decrement();
                        return sb.toString();
                    }
                    throwNotImplementedError();
                }
                else
                    throwNotImplementedError();
            }
            return replace(operand.toString(), "\n", " ");
        }
    }


    private static String replace(String text, String searchString, String replacement) {
        return replace(text, searchString, replacement, -1);
    }

    private static String replace(String text, String searchString, String replacement, int max) {
        if (text.isEmpty() || searchString.isEmpty() || replacement == null || max == 0) {
            return text;
        }
        int start = 0;
        int end = text.indexOf(searchString, start);
        if (end == -1) {
            return text;
        }
        int replLength = searchString.length();
        int increase = replacement.length() - replLength;
        increase = (increase < 0 ? 0 : increase);
        increase *= (max < 0 ? 16 : (max > 64 ? 64 : max));
        StringBuilder buf = new StringBuilder(text.length() + increase);
        while (end != -1) {
            buf.append(text.substring(start, end)).append(replacement);
            start = end + replLength;
            if (--max == 0) {
                break;
            }
            end = text.indexOf(searchString, start);
        }
        buf.append(text.substring(start));
        return buf.toString();
    }

}
