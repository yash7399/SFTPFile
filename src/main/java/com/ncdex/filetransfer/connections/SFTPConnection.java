package com.ncdex.filetransfer.connections;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.ncdex.filetransfer.config.SFTPServer;
import com.ncdex.filetransfer.config.ServerDetails;
import com.ncdex.filetransfer.emails.Emails;
import com.ncdex.filetransfer.utils.Prop;
import com.ncdex.filetransfer.utils.Utils;

@Component
public class SFTPConnection {

	private static final Logger log = LogManager.getLogger(SFTPConnection.class);

	private Properties prop = null;

	private final Emails emails;
	
	private final Utils util;
	
	private final JSch jsch;

	@Autowired
    public SFTPConnection(Emails emails, Utils util, JSch jsch) {
        this.emails = emails;
        this.util = util;
        this.jsch = jsch;
    }

	public  ChannelSftp connect(String serverId) {
		try {
			
			Session session = null;
			ChannelSftp channel = null;


			 Map<String, ServerDetails> server=util.getSFTPServer();
			 
			
			ServerDetails serverDetail=server.get(serverId);
			
			System.out.println(serverDetail.getPrivateKey());
			jsch.addIdentity(serverDetail.getPrivateKey());

			session = jsch.getSession(serverDetail.getUser(), serverDetail.getIp(),
					serverDetail.getPort());

			
//			session.setPassword(getProperty(serverId + ".password"));
			
			session.setServerAliveInterval(60_000);
			session.setServerAliveCountMax(5);

			Properties cfg = new Properties();
			cfg.put("StrictHostKeyChecking", "no");
			session.setConfig(cfg);

			

			session.connect();

			channel = (ChannelSftp) session.openChannel("sftp");

			channel.connect();

			channel.setBulkRequests(256);

			System.out.println("NSE connected successfully ");
			log.info("NSE connected successfully ");

			return channel;
		} catch (Exception e) {
			log.error("Unable to connect with NSE server " + e.getMessage());
			log.error(e);
			emails.connectionIssue();
		}
		return null;

	}

	 public String getProperty(String propName) {
		try {

			if (prop == null) {
				prop = Prop.getProp();
			}
			return prop.getProperty(propName);
		} catch (Exception e) {
			log.error(e);
		}
		return propName;
	}
}
