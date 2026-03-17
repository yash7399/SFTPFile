package com.ncdex.filetransfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FileTransferApplication {
    public static void main(String[] args) {
    		try {
    			
    			SpringApplication.run(FileTransferApplication.class, args);
    		}
    		catch(Exception e) {
    			System.exit(1);
    		}
    		
    }
}
