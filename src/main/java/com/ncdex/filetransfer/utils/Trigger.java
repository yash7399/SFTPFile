package com.ncdex.filetransfer.utils;

import java.util.EnumSet;

import javax.management.RuntimeErrorException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.ncdex.filetransfer.constants.GlobalConstants;


public class Trigger {
	private static final Logger log = LogManager.getLogger(Trigger.class);


public static void makeTriggerFile(DiskShare share, String triggerFolder, String sftpServer) throws Exception {
        try {
        	
            if (triggerFolder.startsWith("\\") || triggerFolder.startsWith("/")) {
                triggerFolder = triggerFolder.replaceFirst("^[\\\\/]+", "");
            }
            
            ensureDirectoryChain(share, triggerFolder);

            String triggerFileName = "Trigger_" + GlobalConstants.batchDate + "_"+sftpServer+".trigger";
            String smbTriggerPath = triggerFolder + "\\" + triggerFileName;
            
            try (File triggerFile = share.openFile(
                    smbTriggerPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.FILE_READ_ATTRIBUTES),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null)) {
                
                triggerFile.write(new byte[0], 0);
                log.info("Trigger file created at: {}\\{}", triggerFolder, triggerFileName);
            }
        } finally {
            share.close();
        }
    }


    private static void ensureDirectoryChain(DiskShare share, String fullPath) {
        String[] parts = fullPath.split("[\\\\/]+"); // split on \ or /
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (current.length() > 0) current.append("\\");
            current.append(parts[i]);

            String dir = current.toString();
            if (!share.folderExists(dir)) {
                log.info("Creating directory: {}", dir);
                share.mkdir(dir);
            }
        }
    }

}
