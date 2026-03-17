package com.ncdex.filetransfer.utils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

import javax.management.RuntimeErrorException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Prop {
	private static final Logger log = LogManager.getLogger(Prop.class);
	private static Properties prop = new Properties();
	
	public static Properties init(String path) {
		
		    	try {
		    		System.out.println(path);
		    		InputStream input=new FileInputStream( path );
		    		
		    		prop.load(input);
		    	}
		    	catch(Exception e){
		    		
		    		log.info("Failed to load config"+ e.getMessage());
		    		log.error(e);
		    		System.exit(1);
		    	}
				return prop;
	}
	
	public static Properties getProp() {
		return prop;
	}
}
