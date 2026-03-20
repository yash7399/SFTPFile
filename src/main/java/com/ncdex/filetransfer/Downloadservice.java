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

/**
 * Phase 1 — downloads files from SFTP server to a local temp folder.
 * Returns a DownloadedFile on success; adds to missingFiles and returns
 * empty on failure (no retries in this phase).
 */
@Component
public class DownloadService {

    private static final Logger log = LogManager.getLogger(DownloadService.class);

    @Autowired
    Emails emails;

    /**
     * Entry point called per FileConfig record.
     * Resolves single-file vs multi-file and delegates accordingly.
     * Returns the list of successfully downloaded files (may be empty).
     */
    public List<DownloadedFile> download(ChannelSftp sftp,
                                         String fileName,
                                         String sourcePath,
                                         String destinationPath,
                                         String department,
                                         Map<String, List<String>> missingFiles) throws Exception {

        if (fileName.contains("N.")) {
            return downloadMultipleFiles(sftp, fileName, sourcePath, destinationPath, department, missingFiles);
        } else {
            List<DownloadedFile> result = new ArrayList<>();
            downloadSingleFile(sftp, fileName, sourcePath, destinationPath, department, missingFiles, result);
            return result;
        }
    }

    // -------------------------------------------------------------------------
    // Single file
    // -------------------------------------------------------------------------

    private void downloadSingleFile(ChannelSftp sftp,
                                    String fileName,
                                    String sourcePath,
                                    String destinationPath,
                                    String department,
                                    Map<String, List<String>> missingFiles,
                                    List<DownloadedFile> result) {

        String sftpFilePath = sourcePath + "/" + fileName;
        try {
            sftp.stat(sftpFilePath);
            DownloadedFile downloaded = pullToLocal(sftp, fileName, sftpFilePath, destinationPath, department);
            if (downloaded != null) {
                result.add(downloaded);
            } else {
                missingFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(fileName);
            }
        } catch (Exception e) {
            log.warn("{}: File not found or download failed — marking missing", sftpFilePath);
            missingFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(fileName);
        }
    }

    // -------------------------------------------------------------------------
    // Multi file (N. pattern)
    // -------------------------------------------------------------------------

    private List<DownloadedFile> downloadMultipleFiles(ChannelSftp sftp,
                                                        String template,
                                                        String sourcePath,
                                                        String destinationPath,
                                                        String department,
                                                        Map<String, List<String>> missingFiles) throws SftpException {

        List<DownloadedFile> result = new ArrayList<>();
        Pattern pattern = buildPattern(template);
        if (pattern == null) {
            log.warn("Could not build pattern for template: {}. Skipping.", template);
            return result;
        }

        String basename  = template.substring(0, template.lastIndexOf('_'));
        String extension = template.substring(template.indexOf('.') + 1);
        String listPath  = sourcePath + "/" + basename + "_*." + extension;

        Vector<ChannelSftp.LsEntry> files;
        try {
            files = sftp.ls(listPath);
        } catch (SftpException e) {
            log.error("Cannot list SFTP path {}: {}", listPath, e.getMessage());
            missingFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(template);
            return result;
        }

        if (files == null || files.isEmpty()) {
            log.warn("No files matched pattern {} in {}", template, sourcePath);
            missingFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(template);
            return result;
        }

        for (ChannelSftp.LsEntry entry : files) {
            if (!pattern.matcher(entry.getFilename()).matches()) continue;
            String remoteFile = entry.getFilename();
            try {
                DownloadedFile downloaded = pullToLocal(sftp, remoteFile, sourcePath + "/" + remoteFile, destinationPath, department);
                if (downloaded != null) {
                    result.add(downloaded);
                } else {
                    missingFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(remoteFile);
                }
            } catch (Exception e) {
                log.warn("{}: Download failed — marking missing", remoteFile);
                missingFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(remoteFile);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Core: SFTP -> local temp
    // -------------------------------------------------------------------------

    /**
     * Downloads one file from SFTP to the local temp folder.
     * Returns a DownloadedFile (with the local path and computed hash) on
     * success, or null on failure (caller adds to missingFiles).
     */
    private DownloadedFile pullToLocal(ChannelSftp sftp,
                                       String fileName,
                                       String sftpPath,
                                       String destinationPath,
                                       String department) throws Exception {

        Files.createDirectories(Paths.get(GlobalConstants.local_folder_temporary));
        Path tempFile = Paths.get(GlobalConstants.local_folder_temporary, fileName);

        try {
            log.info("Phase 1 | Downloading {} from SFTP", fileName);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in        = sftp.get(sftpPath);
                 DigestInputStream dis = new DigestInputStream(in, md);
                 FileOutputStream  fos = new FileOutputStream(tempFile.toFile())) {

                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = dis.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
            }
            String hash = bytesToHex(md.digest());
            log.info("Phase 1 | Downloaded {} | hash={}", fileName, hash);
            return new DownloadedFile(fileName, tempFile, destinationPath, department);

        } catch (SftpException e) {
            safeDelete(tempFile);
            log.error("Phase 1 | SFTP error downloading {}: {}", fileName, e.getMessage());
            throw e;
        } catch (Exception e) {
            safeDelete(tempFile);
            log.error("Phase 1 | Error downloading {}: {}", fileName, e.getMessage());
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void safeDelete(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete temp file {}: {}", path, e.getMessage());
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

        int    digits  = dotIndex - lastUnderscore - 1;
        String prefix  = Pattern.quote(template.substring(0, lastUnderscore));
        String ext     = Pattern.quote(template.substring(dotIndex + 1));

        if (digits <= 0) return null;
        return Pattern.compile("^" + prefix + "_[0-9]{" + digits + "}\\." + ext + "$");
    }
}
