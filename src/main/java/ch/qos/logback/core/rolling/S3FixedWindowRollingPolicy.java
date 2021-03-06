package ch.qos.logback.core.rolling;


import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;

/**
 * Extension of FixedWindowRollingPolicy.
 * <p/>
 * On each rolling event (which is defined by <triggeringPolicy>), this policy does:
 * 1. Regular log file rolling as FixedWindowsRollingPolicy does
 * 2. Upload the rolled log file to S3 bucket
 * <p/>
 * Also, this policy uploads the active log file on JVM exit. If rollingOnExit is true,
 * another log rolling happens and a rolled log is uploaded. If rollingOnExit is false,
 * the active file is directly uploaded.
 * <p/>
 * If rollingOnExit is false and if no rolling happened before JVM exits, this rolling
 * policy uploads the active log file as it is.
 */
public class S3FixedWindowRollingPolicy extends FixedWindowRollingPolicy {

    private ExecutorService executor = Executors.newFixedThreadPool(1);

    private String awsAccessKey;
    private String awsSecretKey;
    private String s3BucketName;
    private String s3FolderName;

    private boolean rollingOnExit = true;

    private AmazonS3Client s3Client;

    protected synchronized  AmazonS3Client getS3Client() {
        if (s3Client == null) {
            AWSCredentials cred = credentials();
            s3Client = cred != null ? new AmazonS3Client(cred) : new AmazonS3Client();
        }
        return s3Client;
    }

    private AWSCredentials credentials() {
        return getAwsAccessKey() != null && getAwsSecretKey() != null ? new BasicAWSCredentials(getAwsAccessKey(), getAwsSecretKey()) : null;
    }

    @Override
    public void start() {
        super.start();
        // add a hook on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHookRunnable()));
    }

    @Override
    public void rollover() throws RolloverFailure {
        super.rollover();

        // upload the current log file into S3
        String rolledLogFileName = fileNamePattern.convertInt(getMinIndex());
        uploadFileToS3Async(rolledLogFileName);
    }

    protected void uploadFileToS3Async(String filename) {
        final File file = new File(filename);

        // if file does not exist or empty, do nothing
        if (!file.exists() || file.length() == 0) {
            return;
        }

        // add the S3 folder name in front if specified
        final StringBuilder s3ObjectName = new StringBuilder();
        if (getS3FolderName() != null) {
            s3ObjectName.append(getS3FolderName()).append("/");
        }
        s3ObjectName.append(file.getName());

        addInfo("Uploading " + filename);
        Runnable uploader = new Runnable() {
            @Override
            public void run() {
                try {
                    getS3Client().putObject(getS3BucketName(), s3ObjectName.toString(), file);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };
        executor.execute(uploader);
    }

    // On JVM exit, upload the current log
    class ShutdownHookRunnable implements Runnable {

        @Override
        public void run() {
            try {
                if (isRollingOnExit())
                    // do rolling and upload the rolled file on exit
                    rollover();
                else
                    // upload the active log file without rolling
                    uploadFileToS3Async(getActiveFileName());

                // wait until finishing the upload
                executor.shutdown();
                executor.awaitTermination(10, TimeUnit.MINUTES);
            } catch (Exception ex) {
                addError("Failed to upload a log in S3", ex);
                executor.shutdownNow();
            }
        }

    }


    public String getAwsAccessKey() {
        return awsAccessKey;
    }

    public void setAwsAccessKey(String awsAccessKey) {
        this.awsAccessKey = awsAccessKey;
    }

    public String getAwsSecretKey() {
        return awsSecretKey;
    }

    public void setAwsSecretKey(String awsSecretKey) {
        this.awsSecretKey = awsSecretKey;
    }

    public String getS3BucketName() {
        return s3BucketName;
    }

    public void setS3BucketName(String s3BucketName) {
        this.s3BucketName = s3BucketName;
    }

    public String getS3FolderName() {
        return s3FolderName;
    }

    public void setS3FolderName(String s3FolderName) {
        this.s3FolderName = s3FolderName;
    }

    public boolean isRollingOnExit() {
        return rollingOnExit;
    }

    public void setRollingOnExit(boolean rollingOnExit) {
        this.rollingOnExit = rollingOnExit;
    }

}
