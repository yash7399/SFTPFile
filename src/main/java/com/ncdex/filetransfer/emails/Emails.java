package com.ncdex.filetransfer.emails;



import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.billdeskpro.EmailController.MailSenderController;
import com.ncdex.filetransfer.ApplicationRunner;
import com.ncdex.filetransfer.constants.GlobalConstants;
import com.ncdex.filetransfer.templates.Templates;
import com.ncdex.filetransfer.utils.Prop;

@Component
public class Emails {
	
	private final Logger log= LogManager.getLogger(Emails.class);
	
	static Properties prop = null;

	@Autowired
	Templates templates;

	public synchronized void filesNotFound (ConcurrentMap<String, List<String>> missingFiles,ConcurrentMap<String, List<String>> transferedFiles,String depart, boolean ifConnectionIssue) {
		
			System.out.println("In filesNotFound");
				
				try {
					
					String toEmail = getProperty("email." + depart);
					String subject = GlobalConstants.emailSubject;
					
					List<String> failureFiles = missingFiles.get(depart);
					List<String> successFiles=transferedFiles.get(depart);
					
					String body = templates.filesBody(failureFiles,successFiles, GlobalConstants.batchDate,ifConnectionIssue);
					
					String[] args = { "NCDEX", null, null,
							GlobalConstants.emailConfigPath, "n", null,
							null, subject, body , toEmail};
					
					System.out.printf("%s %s %s",toEmail,subject,body);
					try {
						MailSenderController.main(args);
						
						System.out.println("Sucessfully created email for "+depart);
						log.info("Sucessfully created email for "+depart);
					} catch (Exception e) {
						System.out.println("error occured while mailing "+e.getMessage());
						log.info("error occured while mailing "+e.getMessage());
					}
				}
				catch(Exception e) {
					log.error(e);
				}
			

	}

	public  synchronized void connectionIssue() {
		
		
		System.out.println("In connection issue");
		ConcurrentMap<String, List<String>> missingFiles=ApplicationRunner.getMissingFiles();
		ConcurrentMap<String, List<String>> transferedFiles=ApplicationRunner.getTransferedFiles();
		List<String> departments=ApplicationRunner.getDepartments();
		
		
		
		for(String depart:departments) {
			
			filesNotFound(missingFiles, transferedFiles, depart, true);
		}
		
		System.exit(1);
	}

	public String getProperty(String propName) {
    	try {
    		
    		if (prop==null) {
    			prop=Prop.getProp();
    		}
    		return prop.getProperty(propName);
    	}
    	catch(Exception e) {
    		log.error(e);
    	}
		return propName;
    }
}
