package com.pinterest.secor.uploader;

import com.pinterest.secor.common.LogFilePath;
import com.pinterest.secor.common.SecorConfig;
import com.pinterest.secor.util.FileUtil;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.ipc.StandbyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Manages uploads to S3 using the Hadoop API.
 */
public class HdfsRemergeUploadManager extends UploadManager {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsRemergeUploadManager.class);

    protected static final ExecutorService executor = Executors.newFixedThreadPool(256);

    public HdfsRemergeUploadManager(SecorConfig config) {
        super(config);
    }

    private String[] getResolvedRemoteDestinations(LogFilePath localPath) throws Exception {
        final String prefix = FileUtil.getPrefix(localPath.getTopic(), mConfig);
        final LogFilePath path = localPath.withPrefix(prefix);
        final String pathTemplate = path.getLogFilePath();

        final String[] nameNodes = mConfig.getStringArray("secor.remerge.hdfs.namenode.list");
        final String[] resolved = new String[nameNodes.length];
        for (int idx = 0; idx < nameNodes.length; idx++) {
            resolved[idx] = pathTemplate.replace("${REMERGE_HDFS_NN}", nameNodes[idx]);
        }

        return resolved;
    }

    public Handle<?> upload(LogFilePath localPath) throws Exception {
        final String localLogFilename = localPath.getLogFilePath();
        final String[] resolvedDestinations = getResolvedRemoteDestinations(localPath);

        final Future<?> f = executor.submit(new Runnable() {
            @Override
            public void run() {
                for (String destLogFilename : resolvedDestinations) {
                    try {
                        LOG.info("Uploading file {} to {}", localLogFilename, destLogFilename);
                        FileUtil.moveToCloud(localLogFilename, destLogFilename);
                        return;
                    }
                    catch (RemoteException e) {
                        final IOException wrappedError = e.unwrapRemoteException();

                        if (wrappedError instanceof StandbyException) {
                            LOG.warn("Upload failed due to name node failover", e);
                            continue;
                        }

                        throw new RuntimeException(e);
                    }
                    catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                throw new RuntimeException("All possible destinations were unsuccessful");
            }
        });

        return new FutureHandle(f);
    }
}
