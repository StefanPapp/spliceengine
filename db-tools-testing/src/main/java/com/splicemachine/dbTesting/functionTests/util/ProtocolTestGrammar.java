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
package com.splicemachine.dbTesting.functionTests.util;

import java.util.HashMap;
import java.util.Locale;

/**
 * Describes the grammer / language for testing the DRDA protocol implementation
 * used by Derby.
 * <p>
 * Each command has a corresponding string, that can be used to specify a
 * command sequence with text (for instance in a file).
 */
public enum ProtocolTestGrammar {

    // Commands
    CREATE_DSS_REQUEST,
    CREATE_DSS_OBJECT,
    END_DSS,
    END_DDM_AND_DSS,
    START_DDM,
    END_DDM,
    WRITE_BYTE,
    WRITE_NETWORK_SHORT,
    WRITE_NETWORK_INT,
    WRITE_BYTES,
    WRITE_CODEPOINT_4BYTES,
    WRITE_SCALAR_1BYTE,
    WRITE_SCALAR_2BYTES,
    WRITE_SCALAR_BYTES,
    WRITE_SCALAR_HEADER,
    WRITE_SCALAR_STRING,
    WRITE_SCALAR_PADDED_STRING,
    WRITE_SCALAR_PADDED_BYTES,
    WRITE_SHORT,
    WRITE_INT,
    WRITE_LONG,
    WRITE_FLOAT,
    WRITE_DOUBLE,
    READ_REPLY_DSS,
    READ_LENGTH_AND_CODEPOINT,
    READ_CODEPOINT,
    MARK_COLLECTION,
    GET_CODEPOINT,
    READ_BYTE,
    READ_NETWORK_SHORT,
    READ_SHORT,
    READ_NETWORK_INT,
    READ_INT,
    READ_LONG,
    READ_BOOLEAN,
    READ_STRING,
    READ_BYTES,
    FLUSH,
    DISPLAY,
    CHECKERROR,
    CREATE_DSS_REPLY,
    SKIP_DSS,
    READ_SCALAR_2BYTES,
    READ_SCALAR_1BYTE,
    END_TEST,
    SKIP_DDM,
    INCLUDE,
    SKIP_BYTES,
    WRITE_PADDED_STRING,
    WRITE_STRING,
    WRITE_ENCODED_STRING,
    WRITE_ENCODED_LDSTRING,
    CHECK_SQLCARD,
    MORE_DATA,
    READ_SECMEC_AND_SECCHKCD,
    SWITCH_TO_UTF8_CCSID_MANAGER,
    DELETE_DATABASE;

    /** String associated with the command. */
    private final String cmdString;

    /**
     * Creates a new command and the corresponding string.
     * <p>
     * The string is created by removing all underscore characters and then
     * converting the command name to lower case.
     */
    ProtocolTestGrammar() {
        this.cmdString =
                this.toString().replaceAll("_", "").toLowerCase(Locale.ENGLISH);
    }

    /**
     * Returns the associated string used to identify the command.
     *
     * @return A string representing this command.
     */
    public String toCmdString() {
        return this.cmdString;
    }

    /** Mapping from strings to commands. */
    private static final HashMap<String,ProtocolTestGrammar> CMD_STRINGS =
            new HashMap<String,ProtocolTestGrammar>();

    static {
        // Create a mapping from strings to commands.
        for (ProtocolTestGrammar cmd : ProtocolTestGrammar.values()) {
            CMD_STRINGS.put(cmd.toCmdString(), cmd);
        }
    }

    /**
     * Returns the command corresponding to the specified string.
     *
     * @param cmdStr string representing a command
     * @return The corresponding command if any, {@code null} if there is no
     *      matching command.
     */
    public static ProtocolTestGrammar cmdFromString(String cmdStr) {
        return CMD_STRINGS.get(cmdStr);
    }
}
