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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;

public class EPOUpdater extends Activity {

    private TextView outputView;
    private Button getEpoButton;
    private Button clearGPSButton;
    private Button clearEPOButton;
    private final String localPath = "/data/data/be.drlex.epoblaster/EPO.DAT";
    // There can be two files: if EPOHAL.DAT exists, the GPS system will always use it even if EPO.DAT is more recent
    private final String epoHalPath = "/data/misc/EPOHAL.DAT";
    private final String epoPath = "/data/misc/EPO.DAT";
    private Handler handler = new Handler();

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        outputView = (TextView) findViewById(R.id.outputView);
        getEpoButton = (Button) findViewById(R.id.getEpoButton);
        getEpoButton.setOnClickListener(onEpoButtonClick);
        clearGPSButton = (Button) findViewById(R.id.clearGPSButton);
        clearGPSButton.setOnClickListener(onGPSButtonClick);
        clearEPOButton = (Button) findViewById(R.id.clearEPOButton);
        clearEPOButton.setOnClickListener(onClearEPOButtonClick);

        File epoFile = new File(epoHalPath);
        if (!epoFile.exists()) {
            epoFile = new File(epoPath);
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

            clearOutput();
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        download(url, localPath);
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
                        if (new File(epoHalPath).exists()) {
                            execSU("rm " + epoHalPath);
                        }
                        if (new File(epoPath).exists()) {
                            execSU("rm " + epoPath);
                        }
                        output(getString(R.string.success_clear_epo));
                    } catch (RuntimeException e) {
                        output(getString(R.string.exec_fail) + "! " + e.getLocalizedMessage());
                    }
                }
            });
            thread.start();
        }
    };

    private void installEPO() {
        output(getString(R.string.installing));
        File new_file = new File(localPath);
        if (new_file.length() < 200000) {
            throw new RuntimeException(getString(R.string.too_small));
        }
        String existingPath = epoHalPath;
        File old_file = new File(existingPath);
        if (!old_file.exists()) {
            existingPath = epoPath;
            old_file = new File(existingPath);
        }
        final String epoBackupPath = epoPath + ".old";
        File bak_file = new File(epoBackupPath);
        try {
            String suScript = "";
            if (old_file.exists()) {
                if (bak_file.exists()) {
                    suScript = "rm " + epoBackupPath + "\n";
                }
                suScript += "mv " + existingPath + " " + epoBackupPath + "\n";
            }
            suScript += "mv " + localPath + " " + epoPath + "; chmod 644 " + epoPath + "; chown 1000.1000 " + epoPath;
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
            StringBuffer output = new StringBuffer();
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

    private void download(String urlStr, String localPath) {
        output(getString(R.string.downloading), "");
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
