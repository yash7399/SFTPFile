package com.ncdex.filetransfer.connections;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.ncdex.filetransfer.constants.GlobalConstants;
import com.ncdex.filetransfer.emails.Emails;

import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NASConnection {

    private static final Logger log = LogManager.getLogger(NASConnection.class);
    private  static Emails emails;
//    private  SMBClient client;


    @Autowired
    public NASConnection(Emails emails, SMBClient client) {
        this.emails = emails;
//        this.client = client;
    }

    public static Session connect() {

    	SMBClient client;

    	SmbConfig config = SmbConfig.builder()

    	.withWriteBufferSize(1024*1024)

    	.withReadBufferSize(1024*1024)

    	.withTimeout(30, TimeUnit.MINUTES)

    	.withSoTimeout(30, TimeUnit.MINUTES)

    	.build();

    	client = new SMBClient(config);

    	try {
    		
    	Connection connection = client.connect(GlobalConstants.dcHost);

    	AuthenticationContext auth = new AuthenticationContext(GlobalConstants.dcUser,

    	GlobalConstants.dcPass.toCharArray(), "");

    	Session session = connection.authenticate(auth);

    	System.out.println("Connected to DC NAS successfully!");

    	log.info("Connected to DC NAS successfully!");

    	return session;

    	} catch (Exception e) {

    	System.out.println("DC connection failed");

    	e.printStackTrace();

    	log.info("DC connection failed");

    	System.out.println("Trying to connect with DR");

    	log.info("Trying to connect with DR");

    	try {

    	Connection connection = client.connect(GlobalConstants.drHost);

    	AuthenticationContext auth = new AuthenticationContext(GlobalConstants.drUser,

    	GlobalConstants.drPass.toCharArray(), "");

    	Session session = connection.authenticate(auth);

    	System.out.println("Connected to DR NAS successfully!");

    	log.info("Connected to DR NAS successfully!");

    	return session;

    	}

    	catch (Exception ex) {

    	log.error("Unable to connect with NAS server" + e.getMessage());

    	log.error(e);

    	System.out.println("Unable to connect with NAS server");

    	emails.connectionIssue();

    	}

    	}

    	return null;

    	}

}