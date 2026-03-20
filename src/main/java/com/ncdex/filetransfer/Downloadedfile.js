package com.ncdex.filetransfer;

import java.nio.file.Path;

/**
 * Handoff object between Phase 1 (download) and Phase 2 (NAS upload).
 * Carries everything Phase 2 needs without re-reading config.
 */
public class DownloadedFile {

    private final String fileName;
    private final Path   localPath;       // file in local temp folder
    private final String destination;     // NAS sub-folder (relative to mount point)
    private final String department;

    public DownloadedFile(String fileName, Path localPath, String destination, String department) {
        this.fileName    = fileName;
        this.localPath   = localPath;
        this.destination = destination;
        this.department  = department;
    }

    public String getFileName()    { return fileName; }
    public Path   getLocalPath()   { return localPath; }
    public String getDestination() { return destination; }
    public String getDepartment()  { return department; }
}
