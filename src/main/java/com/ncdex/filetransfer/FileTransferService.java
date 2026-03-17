package com.ncdex.filetransfer;

import java.io.*;
import java.nio.channels.FileChannel;
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
    private static final String OS = System.getProperty("os.name").toLowerCase();

    // Configuration for OS Mount (Update these via properties)
    private final String driveLetter = "Z:"; 
    private final String mountPoint = "/";
    private final String nasPath = "\\\\172.29.3.27\\nseit"; // Use // for Linux
    private final String nasUser = "mohds";
    private final String nasPass = "Ntick@2026";

    public void transfer(String serverCode, ChannelSftp sftp, String fileName, String sourcePath, 
                         String destinationPath, String department, Map<String, List<String>> missingFiles, 
                         Map<String, List<String>> transferedFiles) throws Exception {

        // 1. MOUNT DRIVE
        mountNas();

        try {
            sftp.stat(sourcePath);
        } catch (Exception e) {
            missingFiles.computeIfAbsent(department, k -> new ArrayList<>()).add(fileName);
            return;
        }

        if (fileName.contains("N.")) {
            transferMultipleFiles(sftp, fileName, sourcePath, destinationPath, department, missingFiles, transferedFiles);
        } else {
            transferSingleFile(sftp, fileName, sourcePath, destinationPath, department, missingFiles, transferedFiles);
        }
    }

    private void transferSingleFile(ChannelSftp sftp, String fileName, String sourcePath, String destPath,
            String dept, Map<String, List<String>> missing, Map<String, List<String>> success) throws Exception {
        
        String sftpFilePath = sourcePath + "/" + fileName;
        try {
            sftp.stat(sftpFilePath);
            copyFileToMountedNas(sftp, fileName, sftpFilePath, destPath);
            success.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
        } catch (Exception e) {
            missing.computeIfAbsent(dept, k -> new ArrayList<>()).add(fileName);
            throw e;
        }
    }

    private void copyFileToMountedNas(ChannelSftp sftp, String fileName, String sftpPath, String subFolder) throws Exception {
        String localTemp = GlobalConstants.local_folder_temporary + "/" + fileName;
        
        // Resolve Final OS Path
        String baseMount = OS.contains("win") ? driveLetter : mountPoint;
        Path finalNasDir = Paths.get(baseMount, subFolder);
        Path finalNasFile = finalNasDir.resolve(fileName);

        Files.createDirectories(Paths.get(GlobalConstants.local_folder_temporary));
        Files.createDirectories(finalNasDir);

        String sourceHash = "";
        String uploadHash = "";

        // 1. SFTP -> LOCAL (With Hash)
        MessageDigest mdSource = MessageDigest.getInstance("SHA-256");
        try (InputStream in = sftp.get(sftpPath);
             DigestInputStream dis = new DigestInputStream(in, mdSource);
             FileOutputStream fos = new FileOutputStream(localTemp)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = dis.read(buffer)) != -1) fos.write(buffer, 0, read);
            sourceHash = bytesToHex(mdSource.digest());
        }

        // 2. LOCAL -> MOUNTED NAS (With Hash)
        MessageDigest mdUpload = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(localTemp);
             FileOutputStream fos = new FileOutputStream(finalNasFile.toFile())) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                mdUpload.update(buffer, 0, read);
            }
            uploadHash = bytesToHex(mdUpload.digest());
        }

        // 3. VERIFY
        if (sourceHash.equalsIgnoreCase(uploadHash)) {
            log.info("Verified: " + fileName);
            Files.deleteIfExists(Paths.get(localTemp));
        } else {
            Files.deleteIfExists(finalNasFile);
            throw new Exception("Checksum Mismatch on Mounted Drive!");
        }
    }

    private void mountNas() throws IOException, InterruptedException {
        ProcessBuilder pb;
        if (OS.contains("win")) {
            // Check if already mounted
            if (new File(driveLetter + "\\").exists()) return;
            pb = new ProcessBuilder("net", "use", driveLetter, nasPath, nasPass, "/user:" + nasUser, "/persistent:no");
        } else {
            if (Files.exists(Paths.get(mountPoint)) && Files.list(Paths.get(mountPoint)).findAny().isPresent()) return;
            Files.createDirectories(Paths.get(mountPoint));
            pb = new ProcessBuilder("sudo", "mount", "-t", "cifs", "-o", "username=" + nasUser + ",password=" + nasPass, nasPath, mountPoint);
        }
        
        Process p = pb.start();
        if (p.waitFor() != 0) {
            log.error("Mount failed. Ensure NAS is reachable.");
        }
    }

    private String bytesToHex(byte[] hash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void transferMultipleFiles(ChannelSftp sftp, String template, String src, String dest, String dept, 
                                      Map<String, List<String>> missing, Map<String, List<String>> success) throws Exception {
        String basename = template.substring(0, template.lastIndexOf('_'));
        String extension = template.substring(template.indexOf('.') + 1);
        Pattern pattern = buildPattern(template);
        if (pattern == null) return;

        Vector<ChannelSftp.LsEntry> files = sftp.ls(src + "/" + basename + "_*." + extension);
        for (ChannelSftp.LsEntry entry : files) {
            if (pattern.matcher(entry.getFilename()).matches()) {
                copyFileToMountedNas(sftp, entry.getFilename(), src + "/" + entry.getFilename(), dest);
                success.computeIfAbsent(dept, k -> new ArrayList<>()).add(entry.getFilename());
            }
        }
    }

    private Pattern buildPattern(String f) {
        String ext = f.substring(f.indexOf('.') + 1);
        int digits = f.indexOf('.') - f.lastIndexOf('_') - 1;
        if (digits <= 0) return null;
        return Pattern.compile("^" + f.substring(0, f.lastIndexOf('_')) + "_[0-9]{" + digits + "}\\." + ext + "$");
    }
}