package com.ncdex.filetransfer.constants;

import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ncdex.filetransfer.ApplicationRunner;
import com.ncdex.filetransfer.utils.Prop;


public class GlobalConstants {
	
	private static final Logger log = LogManager.getLogger(GlobalConstants.class);
		
		static Properties prop=null;

	    public static String dcHost = getProperty("dcHost");
	    public static String dcUser = getProperty("dcUser");
	    public static String dcPass = getProperty("dcPass");
	    
	    public static String drHost = getProperty("drHost");
	    public static String drUser = getProperty("drUser");
	    public static String drPass = getProperty("drPass");
	    
	    public static int no_of_threads =Integer.parseInt(getProperty("no_of_threads")) ;
	    
	    public static String emailSubject = getProperty("email.Subject");
	    
	    public static String departmentDetails = getProperty("path.departmentDetails");
	    public static String SFTPServer = getProperty("path.SFTPServer");
	    
	    public static String filesBodyPath = getProperty("path.filesBody");
	    public static String connectionBodyPath = getProperty("path.connectionBody");
	    public static String emailConfigPath = getProperty("path.emailConfig");
	    public static String local_folder_temporary = getProperty("path.local_folder_temporary");
	    public static String log4j2 = getProperty("path.log4j2");
	    public static String mount_point = getProperty("mount_point");
	    
	    public static String batchDate=ApplicationRunner.getBatchDate();
	    
	    
	    static public String getProperty(String propName) {
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

