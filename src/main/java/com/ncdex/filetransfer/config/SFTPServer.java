package com.ncdex.filetransfer.config;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SFTPServer {
    
    @JsonProperty("sftpserver")
    private Map<String, ServerDetails> server;

   
    public Map<String, ServerDetails> getSFTPServer() {
        return server;
    }

    
    public void setSftpserver(Map<String, ServerDetails> server) {
        this.server = server;
    }
}