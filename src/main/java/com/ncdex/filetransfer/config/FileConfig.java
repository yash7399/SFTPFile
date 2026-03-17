package com.ncdex.filetransfer.config;

public class FileConfig {
	
	private String filename;
	private String source;
	private String share;
	private String destination;
	private int time;
	
	public String getShare() {
		return share;
	}
	public void setShare(String share) {
		this.share = share;
	}
	
	public String getDestination() {
		return destination;
	}
	public void setDestination(String destination) {
		this.destination = destination;
	}
	public int getTime() {
		return time;
	}
	public void setTime(int time) {
		this.time = time;
	}
	public String getFilename() {
		return filename;
	}
	public void setFilename(String filename) {
		this.filename = filename;
	}
	public String getSource() {
		return source;
	}
	public void setSource(String source) {
		this.source = source;
	}
	
	
}
