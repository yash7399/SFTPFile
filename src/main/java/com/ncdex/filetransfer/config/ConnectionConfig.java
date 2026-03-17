package com.ncdex.filetransfer.config;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.jcraft.jsch.JSch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

@Configuration
public class ConnectionConfig {

    @Bean
    public SMBClient smbClient() {
     
        SmbConfig config = SmbConfig.builder()
                .withWriteBufferSize(1024 * 1024)
                .withReadBufferSize(1024 * 1024)
                .withTimeout(30, TimeUnit.MINUTES)
                .withSoTimeout(30, TimeUnit.MINUTES)
                .build();

        return new SMBClient(config);
    }
    
    @Bean
    public JSch jsch() {
        return new JSch();
    }
}