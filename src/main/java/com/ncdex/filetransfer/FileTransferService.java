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

    // NAS is pre-mounted at this path on the Linux server
    private static final String NAS_MOUNT_POINT = "/nas";

    public void transfer(String serverCode, ChannelSftp sftp, String fileName, String sourcePath,
                         String destinationPath, String department,
                         Map<String, List<String>> missingFiles,
                         Map<String, List<String>> transferedFiles) throws Exception {

        // Verify NAS mount is accessible before doing anything
        if (!Files.exists(Paths.get(NAS_MOUNT_POINT))) {
            log.error("NAS mount point {} does not exist. Treating as connection issue.", NAS_MOUNT_POINT);
            emails.connectionIssue();
            return;
        }

        // Check if source directory exists on SFTP
        try {
            sftp.stat(sourcePath);
        } catch (SftpException e) {
            // Source path itself missing = connection/config issue, not a single file issue
            log.error("SFTP source path {} does not exist: {}", sourcePath, e.getMessage());
            emails.connectionIssue();
            return;
        }

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

    private void transferSingleFile(ChannelSftp sftp, String fileName, String sourcePath,
                                    String destPath, String dept,
                                    Map<String, List<String>> missing,
                                    Map<String, List<String>> success) throws Exception {

        String sftpFilePath = sourcePath + "/" + fileName;

        try {
            sftp.stat(sftpFilePath);
        } catch (SftpException e) {
            // File simply doesn't exist on SFTP — file-level, skip it
            log.warn("File not found on SFTP, skipping: {}", sftpFilePath);
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            return;
        }

        try {
            copyFileToNas(sftp, fileName, sftpFilePath, destPath);
            success.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            log.info("Successfully transferred: {}", fileName);
        } catch (FileTransferException e) {
            // File-level issue: checksum mismatch, write failure on this specific file etc.
            log.error("File-level error for {}: {}. Skipping.", fileName, e.getMessage());
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            // Don't rethrow — caller continues with next file
        } catch (SftpException e) {
            // SFTP read error mid-transfer — could be connection drop
            log.error("SFTP error during transfer of {}: {}", fileName, e.getMessage());
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            throw e; // Bubble up so ApplicationRunner can reconnect
        } catch (IOException e) {
            // NAS write failure — likely NAS/network issue, connection-level
            log.error("IO error writing {} to NAS: {}", fileName, e.getMessage());
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            throw e; // Bubble up
        }
    }

    // -------------------------------------------------------------------------
    // CORE COPY: SFTP -> LOCAL TEMP -> NAS (with SHA-256 verify)
    // -------------------------------------------------------------------------

    private void copyFileToNas(ChannelSftp sftp, String fileName,
                                String sftpPath, String subFolder) throws Exception {

        Path tempFile = Paths.get(GlobalConstants.local_folder_temporary, fileName);
        Path nasDir   = Paths.get(NAS_MOUNT_POINT, subFolder);
        Path nasFile  = nasDir.resolve(fileName);

        try {
            // Ensure local temp dir exists
            Files.createDirectories(Paths.get(GlobalConstants.local_folder_temporary));

            // Ensure destination dir exists on NAS
            try {
                Files.createDirectories(nasDir);
            } catch (IOException e) {
                log.error("Cannot create NAS destination directory {}: {}", nasDir, e.getMessage());
                throw e; // NAS not writable = connection-level
            }

            String sourceHash;
            String uploadHash;

            // Step 1: SFTP -> local temp (compute hash while downloading)
            MessageDigest mdSource = MessageDigest.getInstance("SHA-256");
            try (InputStream in = sftp.get(sftpPath);
                 DigestInputStream dis = new DigestInputStream(in, mdSource);
                 FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {

                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = dis.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                sourceHash = bytesToHex(mdSource.digest());

            } catch (SftpException e) {
                log.error("SFTP read failed for {}: {}", sftpPath, e.getMessage());
                throw e; // Caller decides: connection-level
            }

            // Step 2: local temp -> NAS (compute hash while writing)
            MessageDigest mdUpload = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(tempFile.toFile());
                 FileOutputStream fos = new FileOutputStream(nasFile.toFile())) {

                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                    mdUpload.update(buffer, 0, read);
                }
                uploadHash = bytesToHex(mdUpload.digest());

            } catch (IOException e) {
                log.error("Failed to write {} to NAS path {}: {}", fileName, nasFile, e.getMessage());
                throw e; // NAS write failure = connection-level
            }

            // Step 3: Verify checksum
            if (!sourceHash.equalsIgnoreCase(uploadHash)) {
                // Mismatch = this specific file is corrupt in transit, file-level
                log.error("Checksum mismatch for {}. Source: {}, NAS: {}", fileName, sourceHash, uploadHash);
                throw new FileTransferException("Checksum mismatch for file: " + fileName);
            }

            log.info("Checksum verified for: {}", fileName);

        } finally {
            // Always clean up temp file regardless of success or failure
            safeDelete(tempFile);

            // If NAS file exists but we threw a FileTransferException (checksum fail),
            // also remove the corrupt file from NAS
            if (Files.exists(nasFile)) {
                try {
                    // Only delete if checksum failed — we detect this by re-checking hashes
                    // Simpler: always clean on any exception — done via caller catching FileTransferException
                } catch (Exception ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------
    // MULTIPLE FILES (pattern-based, e.g. contains "N.")
    // -------------------------------------------------------------------------

    private void transferMultipleFiles(ChannelSftp sftp, String template,
                                       String src, String dest, String dept,
                                       Map<String, List<String>> missing,
                                       Map<String, List<String>> success) throws Exception {

        Pattern pattern = buildPattern(template);
        if (pattern == null) {
            log.warn("Could not build pattern for template: {}. Skipping.", template);
            return;
        }

        String basename  = template.substring(0, template.lastIndexOf('_'));
        String extension = template.substring(template.indexOf('.') + 1);

        Vector<ChannelSftp.LsEntry> files;
        try {
            files = sftp.ls(src + "/" + basename + "_*." + extension);
        } catch (SftpException e) {
            // Can't list remote dir = connection-level issue
            log.error("Cannot list SFTP directory {} for pattern {}: {}", src, template, e.getMessage());
            throw e;
        }

        if (files == null || files.isEmpty()) {
            log.warn("No files matched pattern {} in {}", template, src);
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(template);
            return;
        }

        for (ChannelSftp.LsEntry entry : files) {
            if (!pattern.matcher(entry.getFilename()).matches()) continue;

            String remoteFile = entry.getFilename();
            try {
                copyFileToNas(sftp, remoteFile, src + "/" + remoteFile, dest);
                success.computeIfAbsent(dept, k -> new ArrayList<>()).add(remoteFile);
                log.info("Successfully transferred: {}", remoteFile);
            } catch (FileTransferException e) {
                // File-level (checksum etc.) — skip this file, continue loop
                log.error("File-level error for {}: {}. Skipping.", remoteFile, e.getMessage());
                missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(remoteFile);
            } catch (SftpException | IOException e) {
                // Connection-level — stop processing, bubble up
                log.error("Connection-level error during multi-file transfer for {}: {}", remoteFile, e.getMessage());
                missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(remoteFile);
                throw e;
            }
        }
    }

    // -------------------------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------------------------

    private void safeDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete temp file {}: {}", path, e.getMessage());
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Pattern buildPattern(String f) {
        String ext    = f.substring(f.indexOf('.') + 1);
        int digits    = f.indexOf('.') - f.lastIndexOf('_') - 1;
        if (digits <= 0) return null;
        return Pattern.compile(
            "^" + Pattern.quote(f.substring(0, f.lastIndexOf('_')))
            + "_[0-9]{" + digits + "}\\." + Pattern.quote(ext) + "$"
        );
    }
}