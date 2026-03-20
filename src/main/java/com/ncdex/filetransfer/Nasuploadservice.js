package com.ncdex.filetransfer;

import java.io.*;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ncdex.filetransfer.constants.GlobalConstants;
import com.ncdex.filetransfer.emails.Emails;

/**
 * Phase 2 — copies files from local temp folder to the NAS mount point.
 * Retries up to MAX_RETRIES times on failure before marking as missing.
 * Deletes the local temp file on success or after all retries are exhausted.
 */
@Component
public class NASUploadService {

    private static final Logger log         = LogManager.getLogger(NASUploadService.class);
    private static final int    MAX_RETRIES = 2;

    @Autowired
    Emails emails;

    /**
     * Attempts to copy localFile to its NAS destination.
     * On success  → adds to transferedFiles, deletes local temp file.
     * On failure  → retries up to MAX_RETRIES, then adds to missingFiles and deletes temp.
     */
    public void upload(DownloadedFile file,
                       Map<String, List<String>> missingFiles,
                       Map<String, List<String>> transferedFiles) {

        String mountPoint  = GlobalConstants.mount_point;
        Path   nasDir      = Paths.get(mountPoint, file.getDestination());
        Path   nasFile     = nasDir.resolve(file.getFileName());
        Path   localFile   = file.getLocalPath();
        String department  = file.getDepartment();
        String fileName    = file.getFileName();

        // Validate mount point is accessible before attempting anything
        Path rootMount = Paths.get(mountPoint);
        if (!Files.exists(rootMount) || !Files.isDirectory(rootMount)) {
            log.error("Phase 2 | Mount point {} missing or not a directory!", mountPoint);
            emails.connectionIssue();
            return;
        }

        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            attempt++;
            log.info("Phase 2 | Attempt {}/{} | Copying {} to NAS", attempt, MAX_RETRIES, fileName);
            try {
                Files.createDirectories(nasDir);
                Files.copy(localFile, nasFile, StandardCopyOption.REPLACE_EXISTING);

                // Verify checksum
                String localHash = computeHash(localFile);
                String nasHash   = computeHash(nasFile);

                if (!localHash.equalsIgnoreCase(nasHash)) {
                    log.error("Phase 2 | Checksum mismatch for {} on attempt {} | local={} nas={}",
                            fileName, attempt, localHash, nasHash);
                    safeDelete(nasFile);
                    // let while loop retry
                    continue;
                }

                log.info("Phase 2 | Success | {} copied and verified", fileName);
                transferedFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(fileName);
                safeDelete(localFile);
                return; // done

            } catch (NoSuchFileException e) {
                log.error("Phase 2 | NAS path not found: {}", nasDir);
                emails.connectionIssue();
                return;

            } catch (AccessDeniedException e) {
                log.error("Phase 2 | Permission denied on NAS: {}", nasDir);
                // No point retrying a permission issue
                break;

            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("No space left on device")) {
                    log.error("Phase 2 | NAS disk full: {}", mountPoint);
                    emails.connectionIssue();
                    return;
                }
                log.error("Phase 2 | IO error on attempt {} for {}: {}", attempt, fileName, e.getMessage());
                // will retry if attempts remain
            }
        }

        // All retries exhausted (or non-retryable error)
        log.error("Phase 2 | All {} attempts failed for {} — marking missing", MAX_RETRIES, fileName);
        missingFiles.computeIfAbsent(department, k -> Collections.synchronizedList(new ArrayList<>())).add(fileName);
        safeDelete(localFile);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String computeHash(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is    = Files.newInputStream(path);
                 DigestInputStream dis = new DigestInputStream(is, md)) {
                byte[] buffer = new byte[1024 * 1024];
                while (dis.read(buffer) != -1) ;
            }
            return bytesToHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Hash computation failed for " + path, e);
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void safeDelete(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Phase 2 | Could not delete file {}: {}", path, e.getMessage());
        }
    }
}
