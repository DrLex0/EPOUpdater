/*
Copyright (c) 2015, Alexander Thomas
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package be.drlex.epoblaster;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;

public class EPOUpdater extends Activity {

    private static String localPath;

    private final String targetPath = "/data/misc/";
    private final String epoFileName = "EPO.DAT";
    private final String epoMD5Name = "EPO.MD5";
    // There can be two files: if EPOHAL.DAT exists, the GPS system will always use it even if EPO.DAT is more recent
    private final String epoHalFileName = "EPOHAL.DAT";
    private final String epoHalMD5Name = "EPOHAL.MD5";
    // A failed auto-download will usually leave this file behind, so let's clean this up as well
    private final String epoTmpName = "EPOTMP.DAT";
    private TextView outputView;
    private Handler handler = new Handler();

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        localPath = getApplicationContext().getFilesDir().getPath();
        setContentView(R.layout.main);

        outputView = (TextView) findViewById(R.id.outputView);
        Button getEpoButton = (Button) findViewById(R.id.getEpoButton);
        getEpoButton.setOnClickListener(onEpoButtonClick);
        Button clearGPSButton = (Button) findViewById(R.id.clearGPSButton);
        clearGPSButton.setOnClickListener(onGPSButtonClick);
        Button clearEPOButton = (Button) findViewById(R.id.clearEPOButton);
        clearEPOButton.setOnClickListener(onClearEPOButtonClick);

        File epoFile = new File(targetPath + epoHalFileName);
        if (!epoFile.exists()) {
            epoFile = new File(targetPath + epoFileName);
        }
        if (epoFile.exists()) {
            long lastMod = epoFile.lastModified();
            long daysSince = (int) ((System.currentTimeMillis() - lastMod) / 86400000);
            output(getString(R.string.last_modified) + " " + Long.toString(daysSince) + " " + getString(R.string.days_ago)
                    + " (" + new SimpleDateFormat("yyyy/MM/dd").format(new Date(lastMod)) + ")");
        } else {
            output(getString(R.string.no_epo));
        }
    }

    private OnClickListener onEpoButtonClick = new OnClickListener() {
        public void onClick(View v) {
            final String url = getString(R.string.epo_url);
            final String tempEpoPath = localPath + "/" + epoFileName;
            final String tempMD5Path = localPath + "/" + epoMD5Name;

            clearOutput();
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        download(url + epoFileName, tempEpoPath, "EPO");
                        download(url + epoMD5Name, tempMD5Path, "MD5");
                        verifyMD5(tempEpoPath, tempMD5Path);
                        installEPO();
                    } catch (RuntimeException e) {
                        output(getString(R.string.download_fail) + "! " + e.getLocalizedMessage());
                    }
                }
            });
            thread.start();
        }
    };

    private OnClickListener onGPSButtonClick = new OnClickListener() {
        public void onClick(View v) {
            clearOutput();
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        final String mtkGPSPath = "/data/misc/mtkgps.dat";
                        if (new File(mtkGPSPath).exists()) {
                            execSU("rm " + mtkGPSPath);
                        }
                        output(getString(R.string.success_clear_gps));
                    } catch (RuntimeException e) {
                        output(getString(R.string.exec_fail) + "! " + e.getLocalizedMessage());
                    }
                }
            });
            thread.start();
        }
    };

    private OnClickListener onClearEPOButtonClick = new OnClickListener() {
        public void onClick(View v) {
            clearOutput();
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        purgeEPOFiles(true);
                    } catch (RuntimeException e) {
                        output(getString(R.string.exec_fail) + "! " + e.getLocalizedMessage());
                    }
                }
            });
            thread.start();
        }
    };

    private void purgeEPOFiles(boolean verbose) {
        final String epoFilePath = targetPath + epoFileName;
        final String epoMD5Path = targetPath + epoMD5Name;
        final String epoHalPath = targetPath + epoHalFileName;
        final String epoHalMD5Path = targetPath + epoHalMD5Name;
        final String epoTmpPath = targetPath + epoTmpName;

        String arguments = "";
        if (new File(epoFilePath).exists())
            arguments += " " + epoFilePath;
        if (new File(epoMD5Path).exists())
            arguments += " " + epoMD5Path;
        if (new File(epoHalPath).exists())
            arguments += " " + epoHalPath;
        if (new File(epoHalMD5Path).exists())
            arguments += " " + epoHalMD5Path;
        if (new File(epoTmpPath).exists())
            arguments += " " + epoTmpPath;

        if (!arguments.equals("")) {
            execSU("rm" + arguments);
        }
        if (verbose) {
            output(getString(R.string.success_clear_epo));
        }
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            if ((0xff & b) < 0x10) {
                hexString.append("0");
            }
            hexString.append(Integer.toHexString(0xFF & b));
        }
        return hexString.toString();
    }

    private void verifyMD5(final String dataPath, final String md5Path) {
        String computedMD5, downloadedMD5;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            FileInputStream iStream = new FileInputStream(dataPath);
            DigestInputStream dis = new DigestInputStream(iStream, digest);
            // DigestInputStream will update the digest while reading the file.
            byte[] inputBytes = new byte[16384];
            int readBytes;
            do {
                readBytes = dis.read(inputBytes);
            } while (readBytes > -1);
            computedMD5 = bytesToHexString(digest.digest());

            BufferedReader reader = new BufferedReader(new FileReader(md5Path));
            downloadedMD5 = reader.readLine();
            if (downloadedMD5 != null) {
                // The downloaded file tends to be appended with some crap, so truncate it
                downloadedMD5 = downloadedMD5.substring(0, 32);
            } else {
                downloadedMD5 = "";
            }
        } catch (Exception err) {
            throw new RuntimeException(getString(R.string.exception_md5) + ": " + err.getLocalizedMessage());
        }
        if (!computedMD5.equals(downloadedMD5)) {
            throw new RuntimeException(getString(R.string.md5_mismatch));
        }
    }

    private void installEPO() {
        output(getString(R.string.installing));
        File new_file = new File(localPath, epoFileName);
        if (new_file.length() < 200000) {
            throw new RuntimeException(getString(R.string.too_small));
        }

        final String epoPath = targetPath + epoFileName;
        final String md5Path = targetPath + epoMD5Name;
        String existingPath = targetPath + epoHalFileName;
        File old_file = new File(existingPath);
        if (!old_file.exists()) {
            existingPath = epoPath;
            old_file = new File(existingPath);
        }
        final String epoBackupPath = epoPath + ".old";

        try {
            String suScript = "";
            if (old_file.exists()) {
                if (new File(epoBackupPath).exists()) {
                    suScript = "rm " + epoBackupPath + "; ";
                }
                suScript += "mv " + existingPath + " " + epoBackupPath;
                execSU(suScript);
            }
            purgeEPOFiles(false);
            suScript = "mv " + localPath + "/" + epoFileName + " " + epoPath
                    + "; mv " + localPath + "/" + epoMD5Name + " " + md5Path
                    + "; chmod 644 " + epoPath + "; chown 1000.1000 " + epoPath
                    + "; chmod 644 " + md5Path + "; chown 1000.1000 " + md5Path;
            execSU(suScript);
            output(getString(R.string.success_epo));
        } catch (RuntimeException e) {
            output(getString(R.string.exec_fail));
        }
    }

    // Executes UNIX command using SU binary.
    private String execSU(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream outStream = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            outStream.writeBytes(command + "\n");
            outStream.writeBytes("exit\n");
            outStream.flush();
            String readString;
            StringBuilder output = new StringBuilder();
            while ((readString = reader.readLine()) != null) {
                output.append(readString);
            }
            reader.close();
            process.waitFor();
            if (process.exitValue() != 0) {
                throw new RuntimeException("SU exited with " + process.exitValue());
            }
            return output.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void download(String urlStr, String localPath, final String what) {
        output(getString(R.string.downloading), " (" + what + ")");
        try {
            URL url = new URL(urlStr);
            HttpURLConnection urlconn = (HttpURLConnection) url.openConnection();
            urlconn.setRequestMethod("GET");
            urlconn.setInstanceFollowRedirects(true);
            urlconn.connect();
            InputStream in = urlconn.getInputStream();
            FileOutputStream out = new FileOutputStream(localPath);
            int read;
            int lastRead = 0;
            byte[] buffer = new byte[4096];
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                lastRead += read;
                if (lastRead >= 16384) {
                    output(".", "");
                    lastRead = 0;
                }
            }
            out.close();
            in.close();
            urlconn.disconnect();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            output("");
        }
    }

    private void clearOutput() {
        outputView.setText("");
    }

    private void output(final String str, final String suffix) {
        Runnable proc = new Runnable() {
            public void run() {
                outputView.setText(outputView.getText() + str + suffix);
            }
        };
        handler.post(proc);
    }

    private void output(final String str) {
        output(str, "\n");
    }
}
