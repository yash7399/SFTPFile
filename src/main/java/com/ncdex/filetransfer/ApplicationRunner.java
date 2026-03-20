package com.ncdex.filetransfer;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

import javax.management.RuntimeErrorException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.jcraft.jsch.ChannelSftp;
import com.ncdex.filetransfer.config.FileConfig;
import com.ncdex.filetransfer.connections.SFTPConnection;
import com.ncdex.filetransfer.constants.GlobalConstants;
import com.ncdex.filetransfer.emails.Emails;
import com.ncdex.filetransfer.utils.Prop;
import com.ncdex.filetransfer.utils.Trigger;
import com.ncdex.filetransfer.utils.Utils;

@Component
public class ApplicationRunner implements CommandLineRunner {

    private static final Logger log = LogManager.getLogger(ApplicationRunner.class);

    private static String batchDate;

    @Autowired Utils          util;
    @Autowired Emails         emails;
    @Autowired SFTPConnection sftpConnection;
    @Autowired DownloadService   downloadService;
    @Autowired NASUploadService  nasUploadService;

    // Shared state — use ConcurrentHashMap with synchronizedList values
    // (see DownloadService / NASUploadService which wrap lists with Collections.synchronizedList)
    private final static ConcurrentMap<String, List<String>> missingFiles    = new ConcurrentHashMap<>();
    private final static ConcurrentMap<String, List<String>> transferedFiles = new ConcurrentHashMap<>();
    private static List<String> departments = new ArrayList<>();

    public static String                              getBatchDate()      { return batchDate; }
    public static ConcurrentMap<String, List<String>> getMissingFiles()   { return missingFiles; }
    public static ConcurrentMap<String, List<String>> getTransferedFiles(){ return transferedFiles; }
    public static List<String>                        getDepartments()    { return departments; }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    @Override
    public void run(String... args) {

        if (args.length < 2) {
            System.exit(1);
        }

        batchDate = args[0];
        String connectionCode = args[1];

        // Resolve config path
        String configpath = null;
        for (String arg : args) {
            if (arg.startsWith("--spring.config.location=")) {
                configpath = arg.split("=", 2)[1];
                break;
            }
        }
        if (configpath == null) {
            System.out.println("NO config path");
            System.exit(1);
        }

        Prop.init(configpath);

        // Configure log4j2
        Path log4jPath = Paths.get(Prop.getProp().getProperty("path.log4j2"));
        if (!Files.exists(log4jPath)) {
            throw new RuntimeErrorException(null, "Log4j2.xml not found: " + log4jPath);
        }
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.setConfigLocation(log4jPath.toUri());

        log.info("*********** APPLICATION STARTED ***********");

        int noOfThreads = GlobalConstants.no_of_threads > 0 ? GlobalConstants.no_of_threads : 2;

        try {
            Map<String, Map<String, List<FileConfig>>> departmentFiles = util.getDepartmentDetails();
            if (departmentFiles == null) {
                log.error("Failed to load department details. Shutting down.");
                System.exit(1);
            }

            for (String dept : departmentFiles.keySet()) {
                departments.add(dept);
            }

            // ----------------------------------------------------------------
            // Phase 1 — download all files from SFTP to local temp
            // Collect all DownloadedFile objects into a thread-safe list
            // ----------------------------------------------------------------
            log.info("*** PHASE 1 START — downloading all files from SFTP ***");

            // One SFTP channel per thread. We use a simple pool keyed by connectionCode.
            // Each task gets its own channel via the submit lambda.
            List<Future<List<DownloadedFile>>> downloadFutures = new ArrayList<>();
            ExecutorService phase1Pool = Executors.newFixedThreadPool(noOfThreads);

            for (Map.Entry<String, Map<String, List<FileConfig>>> deptEntry : departmentFiles.entrySet()) {
                String department = deptEntry.getKey();
                Map<String, List<FileConfig>> connections = deptEntry.getValue();

                for (Map.Entry<String, List<FileConfig>> connEntry : connections.entrySet()) {
                    String currentConnectionCode = connEntry.getKey();
                    List<FileConfig> records     = connEntry.getValue();

                    if (!connectionCode.equals("00") && !connectionCode.equals(currentConnectionCode)) {
                        continue;
                    }

                    Future<List<DownloadedFile>> future = phase1Pool.submit(() ->
                            downloadBatch(currentConnectionCode, department, records, args));
                    downloadFutures.add(future);
                }
            }

            phase1Pool.shutdown();
            phase1Pool.awaitTermination(2, TimeUnit.HOURS);

            // Collect all successfully downloaded files (barrier point)
            List<DownloadedFile> allDownloaded = new ArrayList<>();
            for (Future<List<DownloadedFile>> f : downloadFutures) {
                try {
                    allDownloaded.addAll(f.get());
                } catch (ExecutionException e) {
                    log.error("Phase 1 task threw an exception: {}", e.getCause().getMessage());
                }
            }

            log.info("*** PHASE 1 COMPLETE — {} files downloaded, starting Phase 2 ***", allDownloaded.size());

            // ----------------------------------------------------------------
            // Phase 2 — move all downloaded files from local temp to NAS
            // ----------------------------------------------------------------
            log.info("*** PHASE 2 START — uploading files to NAS ***");

            ExecutorService phase2Pool = Executors.newFixedThreadPool(noOfThreads);
            List<Future<?>> uploadFutures = new ArrayList<>();

            for (DownloadedFile file : allDownloaded) {
                Future<?> future = phase2Pool.submit(() ->
                        nasUploadService.upload(file, missingFiles, transferedFiles));
                uploadFutures.add(future);
            }

            phase2Pool.shutdown();
            phase2Pool.awaitTermination(2, TimeUnit.HOURS);

            // Wait for all uploads to finish
            for (Future<?> f : uploadFutures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    log.error("Phase 2 task threw an exception: {}", e.getCause().getMessage());
                }
            }

            log.info("*** PHASE 2 COMPLETE ***");

            // ----------------------------------------------------------------
            // Trigger files and emails — after both phases done
            // ----------------------------------------------------------------
            for (Map.Entry<String, Map<String, List<FileConfig>>> deptEntry : departmentFiles.entrySet()) {
                String department = deptEntry.getKey();
                Map<String, List<FileConfig>> connections = deptEntry.getValue();

                for (Map.Entry<String, List<FileConfig>> connEntry : connections.entrySet()) {
                    String currentConnectionCode = connEntry.getKey();
                    if (!connectionCode.equals("00") && !connectionCode.equals(currentConnectionCode)) continue;

                    String triggerFolder = Prop.getProp().getProperty("trigger.folder." + department);
                    if (triggerFolder != null) {
                        try {
                            Trigger.makeTriggerFile(triggerFolder, currentConnectionCode);
                            log.info("Trigger file created for {}", department);
                        } catch (Exception e) {
                            log.error("Could not create trigger file for {}: {}", department, e.getMessage());
                        }
                    }
                }
                emails.filesNotFound(missingFiles, transferedFiles, department, false);
            }

            log.info("*********** APPLICATION FINISHED ***********");
            System.exit(0); // success

        } catch (Exception e) {
            log.error("Unexpected error", e);
            emails.connectionIssue();
            System.exit(1);
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1 task — runs inside a thread pool thread
    // Opens its own SFTP channel, downloads all files for one connection batch.
    // Returns the list of DownloadedFile objects for Phase 2.
    // -------------------------------------------------------------------------

    private List<DownloadedFile> downloadBatch(String currentConnectionCode,
                                                String department,
                                                List<FileConfig> records,
                                                String... args) {

        List<DownloadedFile> downloaded = new ArrayList<>();

        ChannelSftp sftp = sftpConnection.connect(currentConnectionCode);
        if (sftp == null) {
            log.error("Phase 1 | Could not connect to SFTP for connection {}", currentConnectionCode);
            // Mark all files in this batch as missing
            for (FileConfig record : records) {
                missingFiles.computeIfAbsent(department,
                        k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(record.getFilename());
            }
            return downloaded;
        }

        try {
            for (FileConfig record : records) {
                String fileName = util.getFileNameWithDate(record.getFilename(), record.getTime(), args[0]);

                // String equality check fixed: use .equals() not ==
                if (fileName.equals(record.getFilename()) &&
                        !record.getFilename().contains("DDMMYYYY") &&
                        !record.getFilename().contains("DD-MON-YYYY")) {
                    log.warn("Phase 1 | Date substitution did not occur for {}", record.getFilename());
                }

                log.info("Phase 1 | Queuing download: {}", fileName);
                try {
                    List<DownloadedFile> result = downloadService.download(
                            sftp, fileName, record.getSource(), record.getDestination(),
                            department, missingFiles);
                    downloaded.addAll(result);
                } catch (Exception e) {
                    log.error("Phase 1 | Error processing {}: {}", fileName, e.getMessage());
                    // Reconnect and continue with remaining files
                    sftp = sftpConnection.connect(currentConnectionCode);
                    if (sftp == null) {
                        log.error("Phase 1 | Reconnect failed for {}. Stopping batch.", currentConnectionCode);
                        break;
                    }
                }
            }
        } finally {
            if (sftp != null && sftp.isConnected()) {
                sftp.disconnect();
                log.info("Phase 1 | SFTP disconnected for connection {}", currentConnectionCode);
            }
        }

        return downloaded;
    }
}
