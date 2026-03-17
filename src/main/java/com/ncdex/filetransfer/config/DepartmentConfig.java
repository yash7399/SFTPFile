package com.ncdex.filetransfer.config;

import java.util.List;
import java.util.Map;

public class DepartmentConfig {
	private Map<String,Map<String, List<FileConfig>>> department;

	public Map<String,Map<String, List<FileConfig>>> getDepartment() {
		return department;
	}

	public void setDepartment(Map<String,Map<String, List<FileConfig>>> department) {
		this.department = department;
	}
	
}
