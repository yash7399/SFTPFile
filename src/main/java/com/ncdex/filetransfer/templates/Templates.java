package com.ncdex.filetransfer.templates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.ncdex.filetransfer.constants.GlobalConstants;




@Component
public class Templates {
	
	private static final Logger log = LogManager.getLogger(Templates.class);

	
	public synchronized String filesBody(List<String> failureFiles,List<String> successFiles, String batchDate,boolean ifConnectionIssue) {

        try {
        	String template=null;
        	if(ifConnectionIssue) {
        		template = Files.readString(
                        Path.of(GlobalConstants.connectionBodyPath)
                );

        	}
        	else {
        		template = Files.readString(
                        Path.of(GlobalConstants.filesBodyPath)
                );

        	}

            String failureFilesList = buildFileList(failureFiles);
            
            String successFilesList = buildFileList(successFiles);

            return template
                    .replace("${batchDate}", batchDate)
                    .replace("${failureFiles}", failureFilesList)
                    .replace("${successFiles}", successFilesList);

        } catch (Exception e) {
        	log.error(e);
        	e.printStackTrace();
            throw new RuntimeException(
                    "Failed to build email body", e
            );
        }
    }

    private String buildFileList(List<String> files) {
        StringBuilder sb = new StringBuilder();
        int i=1;
        if( files==null || files.size()==0 ) return "NUll";
        for (String file : files) {
            sb.append("> ").append(file).append("\n");
        }
        return sb.toString();
    }
	
}
