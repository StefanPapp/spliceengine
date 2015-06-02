package com.splicemachine.derby.impl.services.streams;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.log4j.Logger;

import com.splicemachine.db.iapi.reference.Property;
import com.splicemachine.db.iapi.services.property.PropertyUtil;
import com.splicemachine.db.iapi.services.stream.HeaderPrintWriter;
import com.splicemachine.db.impl.services.stream.SingleStream;
import com.splicemachine.derby.management.StatementManager;

/**
 * @author Scott Fines
 *         Date: 10/9/14
 */
public class ConfiguredStream extends SingleStream {

    private static Logger LOG = Logger.getLogger(ConfiguredStream.class);
	private static String SPLICE_DERBY_LOG = "splice-derby.log";
	
    @Override
    protected HeaderPrintWriter makeStream() {
        String errorFileLocation = PropertyUtil.getSystemProperty(Property.ERRORLOG_FILE_PROPERTY);
        if(errorFileLocation==null){
            /*
             * We haven't explicitly set the error file location in our startup value,
             * so we default it to something nice. In particular, we look for the
             * hbase log4j location to determine where the file should go, and we
             * create a "splice.log" which is located in that directory.
             */
            String hbaseLogDir = PropertyUtil.getSystemProperty("hbase.log.dir");
            if(hbaseLogDir==null)
                hbaseLogDir="."; //if you don't know where it goes, make it relative to here
            String logFileName = PropertyUtil.getSystemProperty("splice.log.file",SPLICE_DERBY_LOG);

            errorFileLocation = hbaseLogDir+"/"+logFileName;
            System.setProperty(Property.ERRORLOG_FILE_PROPERTY,errorFileLocation);
        }

        return super.makeStream();
    }

    protected boolean archiveLogFileIfNeeded(File logFile) {
		boolean archived = false;
		String logFileName = logFile.getName();
		if (!SPLICE_DERBY_LOG.equalsIgnoreCase(logFileName)) {
			return false;
		}
		if (logFile.exists()) {
			int maxFileCount = PropertyUtil.getSystemInt("splice.log.file.max", 100);
			boolean validTarget = false;
			Path targetPath = null;
			String baseName = logFileName + ".%s";
			Path logFilePath = logFile.toPath();
			for (int i = 1; !validTarget && i < maxFileCount; i++) {
				targetPath = logFilePath.resolveSibling(String.format(baseName, i));
				if (!Files.exists(targetPath)) {
					validTarget = true;
				}
			}
			try {
				if (validTarget) {
					Files.move(logFilePath, targetPath);
					archived = true;
				}
			} catch (IOException ioe) {
				archived = false;
				LOG.error(String.format("Exception trying to archive log file %s. We will proceed without archiving it.",
					logFileName), ioe);
			}
		}
		return archived;
	}
}
