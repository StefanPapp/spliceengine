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

package com.splicemachine.db.impl.sql.compile;

import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.reference.ClassName;
import com.splicemachine.db.iapi.services.compiler.MethodBuilder;
import com.splicemachine.db.iapi.services.classfile.VMOpcode;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.ResultDescription;
import com.splicemachine.db.iapi.sql.compile.CompilerContext;
import com.splicemachine.db.iapi.sql.compile.DataSetProcessorType;
import com.splicemachine.db.iapi.sql.compile.Visitor;
import com.splicemachine.db.iapi.sql.execute.ConstantAction;
import com.splicemachine.db.iapi.types.DataTypeDescriptor;
import com.splicemachine.db.impl.sql.GenericColumnDescriptor;
import com.splicemachine.db.iapi.types.TypeId;

import java.util.Collection;

/**
 * @author Jun Yuan
 * Date: 6/9/14
 */
public class ExplainNode extends DMLStatementNode {

    StatementNode node;
    private SparkExplainKind sparkExplainKind;

    public enum SparkExplainKind {
        NONE("none"),
        EXECUTED("executed"),
        LOGICAL("logical"),
        OPTIMIZED("optimized"),
        ANALYZED("analyzed");

        private final String value;

        SparkExplainKind(String value) {
            this.value = value;
        }

        public final String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    int activationKind() { return StatementNode.NEED_NOTHING_ACTIVATION; }

    public String statementToString() { return "Explain"; }

    public void init(Object statementNode,
                     Object sparkExplainKind) {
        node = (StatementNode)statementNode;
        this.sparkExplainKind = (SparkExplainKind)sparkExplainKind;
    }

    /**
     * Used by splice. Provides direct access to the node underlying the explain node.
     * @return the root of the actual execution plan.
     */
    @SuppressWarnings("UnusedDeclaration")
    public StatementNode getPlanRoot(){
        return node;
    }

    @Override
    public void optimizeStatement() throws StandardException {
        if (sparkExplainKind != SparkExplainKind.NONE) {
            getCompilerContext().setDataSetProcessorType(DataSetProcessorType.FORCED_SPARK);
        }
        node.optimizeStatement();
    }

    @Override
    public void bindStatement() throws StandardException {
        node.bindStatement();
    }

    @Override
    public void generate(ActivationClassBuilder acb, MethodBuilder mb) throws StandardException {
        acb.pushGetResultSetFactoryExpression(mb);
        // parameter
        mb.setSparkExplain(sparkExplainKind != SparkExplainKind.NONE);
        node.generate(acb, mb);
        acb.pushThisAsActivation(mb);
        int resultSetNumber = getCompilerContext().getNextResultSetNumber();
        mb.push(resultSetNumber);
        mb.push(sparkExplainKind.toString());
        mb.callMethod(VMOpcode.INVOKEINTERFACE,null, "getExplainResultSet", ClassName.NoPutResultSet, 4);
    }

    @Override
    public ResultDescription makeResultDescription() {
        DataTypeDescriptor dtd = new DataTypeDescriptor(TypeId.getBuiltInTypeId(TypeId.VARCHAR_NAME), true);
        ResultColumnDescriptor[] colDescs = new GenericColumnDescriptor[1];
        String headerString = null;
        switch (sparkExplainKind) {
            case EXECUTED:
                headerString = "\nNative Spark Execution Plan";
                break;
            case LOGICAL :
                headerString = "\nNative Spark Logical Plan";
                break;
            case OPTIMIZED :
                headerString = "\nNative Spark Optimized Plan";
                break;
            case ANALYZED :
                headerString = "\nNative Spark Analyzed Plan";
                break;
            default :
                headerString = "Plan";
                break;
        }
        colDescs[0] = new GenericColumnDescriptor(headerString, dtd);
        String statementType = statementToString();

        return getExecutionFactory().getResultDescription(colDescs, statementType );
    }

    @Override
    public void acceptChildren(Visitor v) throws StandardException {
        super.acceptChildren(v);

        if ( node!= null) {
            node = (StatementNode)node.accept(v, this);
        }
    }

    @Override
    public ConstantAction makeConstantAction() throws StandardException {
        return	node.makeConstantAction();
    }

    @Override
    public void buildTree(Collection<QueryTreeNode> tree, int depth) throws StandardException {
        if ( node!= null)
            node.buildTree(tree,depth);
    }
}
