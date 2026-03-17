package com.ncdex.filetransfer;

import java.io.*;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import com.ncdex.filetransfer.constants.GlobalConstants;
import com.ncdex.filetransfer.emails.Emails;

@Component
public class FileTransferService {

    @Autowired
    Emails emails;

    private static final Logger log = LogManager.getLogger(FileTransferService.class);

    // NAS is already mounted at this drive letter on Windows
    private static final String MOUNT_POINT = "Z:";

    // -------------------------------------------------------------------------
    // ENTRY POINT
    // -------------------------------------------------------------------------

    public void transfer(String serverCode, ChannelSftp sftp, String fileName,
                         String sourcePath, String destinationPath, String department,
                         Map<String, List<String>> missingFiles,
                         Map<String, List<String>> transferedFiles) throws Exception {

        if (fileName.contains("N.")) {
            transferMultipleFiles(sftp, fileName, sourcePath, destinationPath,
                    department, missingFiles, transferedFiles);
        } else {
            transferSingleFile(sftp, fileName, sourcePath, destinationPath,
                    department, missingFiles, transferedFiles);
        }
    }

    // -------------------------------------------------------------------------
    // SINGLE FILE
    // -------------------------------------------------------------------------

    private void transferSingleFile(ChannelSftp sftp, String fileName,
                                    String sourcePath, String destPath, String dept,
                                    Map<String, List<String>> missing,
                                    Map<String, List<String>> success) throws Exception {

        String sftpFilePath = sourcePath + "/" + fileName;

        // Check if file exists on SFTP — file-level if missing
        try {
            sftp.stat(sftpFilePath);
        } catch (SftpException e) {
            log.warn("File not found on SFTP, skipping: {}", sftpFilePath);
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            return; // skip this file, no exception thrown
        }

        // Transfer the file
        copyFile(sftp, fileName, sftpFilePath, destPath, dept, missing, success);
    }

    // -------------------------------------------------------------------------
    // MULTIPLE FILES (pattern-based)
    // -------------------------------------------------------------------------

    private void transferMultipleFiles(ChannelSftp sftp, String template,
                                       String sourcePath, String destPath, String dept,
                                       Map<String, List<String>> missing,
                                       Map<String, List<String>> success) throws Exception {

        Pattern pattern = buildPattern(template);
        if (pattern == null) {
            log.warn("Could not build pattern for template: {}. Skipping.", template);
            return;
        }

        String basename  = template.substring(0, template.lastIndexOf('_'));
        String extension = template.substring(template.indexOf('.') + 1);
        String listPath  = sourcePath + "/" + basename + "_*." + extension;

        Vector<ChannelSftp.LsEntry> files;
        try {
            files = sftp.ls(listPath);
        } catch (SftpException e) {
            // Can't list directory = connection issue
            log.error("Cannot list SFTP path {}: {}", listPath, e.getMessage());
            throw e;
        }

        if (files == null || files.isEmpty()) {
            log.warn("No files matched pattern {} in {}", template, sourcePath);
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(template);
            return;
        }

        for (ChannelSftp.LsEntry entry : files) {
            if (!pattern.matcher(entry.getFilename()).matches()) continue;
            String remoteFile = entry.getFilename();
            copyFile(sftp, remoteFile, sourcePath + "/" + remoteFile, destPath, dept, missing, success);
        }
    }

    // -------------------------------------------------------------------------
    // CORE COPY: SFTP -> LOCAL TEMP -> MOUNTED NAS (with SHA-256 verify)
    // -------------------------------------------------------------------------

   private void copyFile(ChannelSftp sftp, String fileName, String sftpPath,
                      String destSubFolder, String dept,
                      Map<String, List<String>> missing,
                      Map<String, List<String>> success) throws Exception {

    Path tempFile = Paths.get(GlobalConstants.local_folder_temporary, fileName);
    Path nasDir   = Paths.get(MOUNT_POINT, destSubFolder);
    Path nasFile  = nasDir.resolve(fileName);

    String sourceHash = null;
    String nasHash    = null;

    try {
        // Create local temp dir if not exists
        Files.createDirectories(Paths.get(GlobalConstants.local_folder_temporary));

        // Create destination dir on NAS if not exists
        Files.createDirectories(nasDir);

        // Step 1: SFTP -> local temp
        log.info("Downloading {} from SFTP to temp", fileName);
        MessageDigest mdSource = MessageDigest.getInstance("SHA-256");
        try (InputStream in        = sftp.get(sftpPath);
             DigestInputStream dis = new DigestInputStream(in, mdSource);
             FileOutputStream fos  = new FileOutputStream(tempFile.toFile())) {

            byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
            int read;
            while ((read = dis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            sourceHash = bytesToHex(mdSource.digest());
        }
        log.info("Downloaded {} to temp. Hash: {}", fileName, sourceHash);

        // Step 2: local temp -> NAS (Replacing Robocopy with Native Java Copy)
        log.info("Copying {} from temp to NAS: {}", fileName, nasFile);
        
        // StandardCopyOption.REPLACE_EXISTING behaves like Robocopy's overwrite
        Files.copy(tempFile, nasFile, StandardCopyOption.REPLACE_EXISTING);

        // Step 3: Verify checksum (Generate hash for the file now on the NAS)
        MessageDigest mdNas = MessageDigest.getInstance("SHA-256");
        try (InputStream is    = Files.newInputStream(nasFile);
             DigestInputStream dis = new DigestInputStream(is, mdNas)) {
            byte[] buffer = new byte[1024 * 1024];
            while (dis.read(buffer) != -1) ; // Read the file to generate hash
            nasHash = bytesToHex(mdNas.digest());
        }

        if (!sourceHash.equalsIgnoreCase(nasHash)) {
            log.error("Checksum mismatch for {}. SFTP: {} | NAS: {}", fileName, sourceHash, nasHash);
            safeDelete(nasFile); 
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            return; 
        }

        log.info("Checksum verified. Transfer complete: {}", fileName);
        success.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);

    } catch (SftpException | IOException e) {
        log.error("Error while transferring {}: {}", fileName, e.getMessage());
        safeDelete(nasFile);
        throw e; 

    } finally {
        safeDelete(tempFile);
    }
}
    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private void safeDelete(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete file {}: {}", path, e.getMessage());
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Pattern buildPattern(String template) {
        int lastUnderscore = template.lastIndexOf('_');
        int dotIndex       = template.indexOf('.');
        if (lastUnderscore < 0 || dotIndex < 0 || dotIndex <= lastUnderscore) return null;

        int digits     = dotIndex - lastUnderscore - 1;
        String prefix  = Pattern.quote(template.substring(0, lastUnderscore));
        String ext     = Pattern.quote(template.substring(dotIndex + 1));

        if (digits <= 0) return null;
        return Pattern.compile("^" + prefix + "_[0-9]{" + digits + "}\\." + ext + "$");
    }
}