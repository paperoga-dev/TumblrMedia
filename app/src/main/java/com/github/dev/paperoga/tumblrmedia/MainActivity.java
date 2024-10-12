package com.github.dev.paperoga.tumblrmedia;

import android.net.Uri;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.window.layout.WindowMetricsCalculator;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.ReturnCode;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {
    private float fromPos = 0, toPos = 0;
    private boolean fromPosSet = false, toPosSet = false, isVideo = false;
    private int videoDuration = 0;
    private Uri currentFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        final View mainView = findViewById(android.R.id.content).getRootView();

        final TextView logTextView = findViewById(R.id.logTextView);
        logTextView.setMovementMethod(new ScrollingMovementMethod());

        final PlayerView playerView = findViewById(R.id.playerView);
        final Player player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        playerView.setVisibility(View.GONE);

        final ActivityResultLauncher<String[]> openFilePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri == null) {
                    return;
                }

                currentFile = uri;

                FFprobeKit.executeAsync(
                    String.format(Locale.US, "-loglevel error -show_entries stream=codec_type -of csv=p=0 %s", FFmpegKitConfig.getSafParameterForRead(this, currentFile)),
                    session -> {
                        isVideo = session.getOutput().contains("video");

                        mainView.post(() -> {
                            findViewById(R.id.btnSetFrom).setEnabled(true);
                            findViewById(R.id.btnSetTo).setEnabled(true);
                            findViewById(R.id.btnReset).setEnabled(true);
                            findViewById(R.id.btnOpen).setEnabled(true);
                            findViewById(R.id.btnConvert).setEnabled(true);

                            playerView.setVisibility(View.VISIBLE);

                            playerView.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                isVideo ?
                                    LinearLayout.LayoutParams.WRAP_CONTENT :
                                    Math.round(WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).getBounds().height() * 0.3f)
                            ));
                            playerView.requestLayout();

                            player.setMediaItem(MediaItem.fromUri(uri));
                            player.prepare();
                            player.play();
                        });
                    },
                        log -> {
                        }
                );
            }
        );

        final ActivityResultLauncher<String> saveFilePicker = registerForActivityResult(
            new ActivityResultContracts.CreateDocument(isVideo ? "video/mp4" : "audio/mp3"),
            uri -> {
                if (uri == null) {
                    return;
                }

                player.stop();
                playerView.setVisibility(View.GONE);

                findViewById(R.id.btnSetFrom).setEnabled(false);
                findViewById(R.id.btnSetTo).setEnabled(false);
                findViewById(R.id.btnReset).setEnabled(false);
                findViewById(R.id.btnOpen).setEnabled(false);
                findViewById(R.id.btnConvert).setEnabled(false);
                findViewById(R.id.btnKill).setEnabled(true);

                if (isVideo) {
                    final StringBuilder ffProbeOutput = new StringBuilder();

                    final ArrayList<String> ffProbeCmd = new ArrayList<>();
                    ffProbeCmd.add("-hide_banner -select_streams v");
                    if (fromPosSet || toPosSet) {
                        ffProbeCmd.add("-show_frames -skip_frame nokey -show_entries frame=pts_time");
                    }
                    ffProbeCmd.add(FFmpegKitConfig.getSafParameterForRead(this, currentFile));

                    logTextView.append(String.format(Locale.US, "Executing %s\n", String.join(" ", ffProbeCmd)));

                    FFprobeKit.executeAsync(
                        String.join(" ", ffProbeCmd),
                        session -> {
                            if (ReturnCode.isSuccess(session.getReturnCode())) {
                                String[] ffProbeOutputLines = ffProbeOutput.toString().split("\n");
                                TreeSet<Float> ffProbeData = new TreeSet<>();

                                Pattern videoDataRegEx = Pattern.compile("^\\s*Duration: (.*), start: (.*), bitrate: (.*)$");
                                Pattern videoFrameRegEx = Pattern.compile("^pts_time=(.*)$");

                                for (final String line : ffProbeOutputLines) {
                                    Matcher videoDataMatcher = videoDataRegEx.matcher(line);
                                    if (videoDataMatcher.find() && (videoDataMatcher.groupCount() >= 1)) {
                                        String[] timeParts = Objects.requireNonNull(videoDataMatcher.group(1)).split(":");
                                        videoDuration = Integer.parseInt(timeParts[0]) * 3600 + Integer.parseInt(timeParts[1]) * 60 + Math.round(Float.parseFloat(timeParts[2]));
                                    } else if (fromPosSet || toPosSet) {
                                        Matcher videoFrameMatcher = videoFrameRegEx.matcher(line);
                                        if (videoFrameMatcher.find() && videoFrameMatcher.groupCount() >= 1) {
                                            ffProbeData.add(Float.parseFloat(videoFrameMatcher.group(1)));
                                        }
                                    }
                                }

                                ArrayList<String> cmd = new ArrayList<>();
                                cmd.add("-y -hide_banner");

                                if (fromPosSet) {
                                    fromPos = ffProbeData.floor(fromPos);
                                    cmd.add(String.format(Locale.US, "-ss %f", fromPos));
                                    mainView.post(() -> logTextView.append(String.format(Locale.US, "Fixing from = %.2f\n", fromPos)));
                                }

                                if (toPosSet) {
                                    toPos = ffProbeData.ceiling(toPos);
                                    cmd.add(String.format(Locale.US, "-to %f", toPos));
                                    mainView.post(() -> logTextView.append(String.format(Locale.US, "Fixing to = %.2f\n", toPos)));
                                }

                                if (toPosSet && videoDuration > Math.round(toPos)) {
                                    videoDuration -= Math.round(toPos);
                                }

                                if (fromPosSet || toPosSet) {
                                    cmd.add("-noaccurate_seek");
                                }

                                cmd.add(String.format(Locale.US, "-i %s -c:a aac -b:a 192k -vf scale=540:-1 -y -f mp4 %s", FFmpegKitConfig.getSafParameterForRead(this, currentFile), FFmpegKitConfig.getSafParameterForWrite(this, uri)));

                                mainView.post(() -> startFFmpeg(String.join(" ", cmd)));
                            }
                        },
                        log -> {
                            mainView.post(() -> logTextView.append(log.getMessage()));
                            ffProbeOutput.append(log.getMessage());
                        }
                    );
                } else {
                    ArrayList<String> cmd = new ArrayList<>();
                    cmd.add("-y -hide_banner");

                    if (fromPosSet) {
                        cmd.add(String.format(Locale.US, "-ss %f", fromPos));
                        mainView.post(() -> logTextView.append(String.format(Locale.US, "Fixing from = %.2f\n", fromPos)));
                    }

                    cmd.add(String.format(Locale.US, "-i %s -acodec libmp3lame", FFmpegKitConfig.getSafParameterForRead(this, currentFile)));

                    if (toPosSet) {
                        cmd.add(String.format(Locale.US, "-t %f", toPos - (fromPosSet ? fromPos : 0.0f)));
                        mainView.post(() -> logTextView.append(String.format(Locale.US, "Fixing to = %.2f\n", toPos)));
                    }

                    cmd.add(String.format(Locale.US, "-y -f mp3 %s", FFmpegKitConfig.getSafParameterForWrite(this, uri)));
                    mainView.post(() -> startFFmpeg(String.join(" ", cmd)));
                }
            }
        );

        findViewById(R.id.btnOpen).setOnClickListener(v -> openFilePicker.launch(new String[]{"video/*", "audio/*"}));

        findViewById(R.id.btnSetFrom).setOnClickListener(v -> {
            fromPosSet = true;
            fromPos = player.getCurrentPosition() / 1000.0f;
            logTextView.append(String.format(Locale.US,"From = %.2f\n", fromPos));
        });

        findViewById(R.id.btnSetTo).setOnClickListener(v -> {
            toPosSet = true;
            toPos = player.getCurrentPosition() / 1000.0f;
            logTextView.append(String.format(Locale.US,"To = %.2f\n", toPos));
        });

        findViewById(R.id.btnReset).setOnClickListener(v -> {
            fromPosSet = toPosSet = false;
            logTextView.append("Markers cleared\n");
        });

        findViewById(R.id.btnConvert).setOnClickListener(v -> saveFilePicker.launch(isVideo ? "newvideo.mp4" : "newaudio.mp3"));

        findViewById(R.id.btnKill).setOnClickListener(v -> FFmpegKit.cancel());
    }

    private void startFFmpeg(String cmd) {
        final View mainView = findViewById(android.R.id.content).getRootView();
        final TextView logTextView = findViewById(R.id.logTextView);
        final ProgressBar progressBarView = findViewById(R.id.progressBar);

        progressBarView.setMax(videoDuration * 1000);

        logTextView.append(String.format("Executing %s\n", cmd));

        FFmpegKit.executeAsync(
            cmd,
            session -> mainView.post(() -> {
                logTextView.append(
                        String.format(
                                "FFmpeg process exited with state %s and rc %s.%s\n",
                                session.getState(),
                                session.getReturnCode(),
                                session.getFailStackTrace()
                        )
                );
                findViewById(R.id.btnSetFrom).setEnabled(true);
                findViewById(R.id.btnSetTo).setEnabled(true);
                findViewById(R.id.btnReset).setEnabled(true);
                findViewById(R.id.btnOpen).setEnabled(true);
                findViewById(R.id.btnConvert).setEnabled(true);
                findViewById(R.id.btnKill).setEnabled(false);
            }),
            log -> mainView.post(() -> {
                String msg = log.getMessage();
                if (!msg.startsWith("frame ")) {
                    logTextView.append(msg);
                }
            }),
            statistics -> progressBarView.setProgress((int) (statistics.getTime() - (fromPosSet ? Math.round(fromPos * 1000) : 0)))
        );
    }

    @Override
    public void onDestroy() {
        Player player = ((PlayerView) findViewById(R.id.playerView)).getPlayer();

        if (player != null) {
            player.release();
        }

        super.onDestroy();
    }
}
