package io.github.varunj.sangoshthi_receiver;

import android.media.MediaCodec;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecSelector;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.extractor.ExtractorSampleSource;
import com.google.android.exoplayer.upstream.Allocator;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Formatter;
import java.util.Locale;

/**
 * Created by Varun on 23-01-2017.
 */

public class MainActivity extends AppCompatActivity {

    private SurfaceView surfaceView;
    private SeekBar seekPlayerProgress;
    private TextView txtCurrentTime;
    private TextView txtEndTime;
    private ImageButton btnLike;
    private ImageButton btnQuery;
    private LinearLayout mediaController;

    private ExoPlayer exoPlayer;
    private Handler handler;
    private StringBuilder mFormatBuilder;
    private Formatter mFormatter;

    private boolean bAutoplay=true;
    private boolean bIsPlaying=false;
    private boolean bControlsActive=true;
    private int RENDERER_COUNT = 300000;
    private int minBufferMs =    250000;
    private final int BUFFER_SEGMENT_SIZE = 64 * 1024;
    private final int BUFFER_SEGMENT_COUNT = 256;
    private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:40.0) Gecko/20100101 Firefox/40.0";
    private String VIDEO_URI = "/video.mp4";

    // socket stuff
    private ServerSocket serverSocket;
    Handler updateConversationHandler;
    Thread serverThread = null;
    public static final int SERVERPORT = 6000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.video_player_layout);
        surfaceView = (SurfaceView) findViewById(R.id.sv_player);
        mediaController = (LinearLayout) findViewById(R.id.lin_media_controller);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initPlayer(3000);

        if(bAutoplay){
            if(exoPlayer!=null){
                exoPlayer.setPlayWhenReady(true);
                bIsPlaying=true;
                setProgress();
            }
        }

        // socket stuff
        updateConversationHandler = new Handler();
        this.serverThread = new Thread(new ServerThread());
        this.serverThread.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // initialising media control------------------------------------------
    private void initMediaControls() {
        initSurfaceView();
        initSeekBar();
        initTxtTime();
        initBtnLike();
        initBtnQuery();
    }

    private void initSurfaceView() {
        surfaceView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleMediaControls();
            }
        });
    }

    private void initSeekBar() {
        seekPlayerProgress = (SeekBar) findViewById(R.id.mediacontroller_progress);
        seekPlayerProgress.requestFocus();
        seekPlayerProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    // We're not interested in programmatically generated changes to the progress bar's position.
                    return;
                }
                // set not interactive
//                exoPlayer.seekTo(progress*1000);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        seekPlayerProgress.setMax(0);
        seekPlayerProgress.setMax((int) exoPlayer.getDuration()/1000);
    }

    private void initTxtTime() {
        txtCurrentTime = (TextView) findViewById(R.id.time_current);
        txtEndTime = (TextView) findViewById(R.id.player_end_time);
    }

    private void initBtnLike() {
        btnLike = (ImageButton) findViewById(R.id.btnLike);
        btnLike.requestFocus();
        btnLike.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // handle like press
                System.out.println("xxx: " + "user: 1 " + "video: " + VIDEO_URI + " pos: " + exoPlayer.getCurrentPosition() + " action: like");
            }
        });
    }

    private void initBtnQuery() {
        btnQuery = (ImageButton) findViewById(R.id.btnQuery);
        btnQuery.requestFocus();
        btnQuery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // handle query press
                System.out.println("xxx: " + "user: 1 " + "video: " + VIDEO_URI + " pos: " + exoPlayer.getCurrentPosition() + " action: query");
            }
        });
    }
    // done initialising media control------------------------------------------

    private String stringForTime(int timeMs) {
        mFormatBuilder = new StringBuilder();
        mFormatter = new Formatter(mFormatBuilder, Locale.getDefault());
        int totalSeconds =  timeMs / 1000;
        int seconds = totalSeconds % 60;
        int minutes = (totalSeconds / 60) % 60;
        int hours   = totalSeconds / 3600;
        mFormatBuilder.setLength(0);
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        }
        else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    private void setProgress() {
        seekPlayerProgress.setProgress(0);
        seekPlayerProgress.setMax(0);
        seekPlayerProgress.setMax((int) exoPlayer.getDuration()/1000);
        handler = new Handler();
        //Make sure Seekbar is updated only on UI thread
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (exoPlayer != null && bIsPlaying ) {
                    seekPlayerProgress.setMax(0);
                    seekPlayerProgress.setMax((int) exoPlayer.getDuration()/1000);
                    int mCurrentPosition = (int) exoPlayer.getCurrentPosition() / 1000;
                    seekPlayerProgress.setProgress(mCurrentPosition);
                    txtCurrentTime.setText(stringForTime((int)exoPlayer.getCurrentPosition()));
                    txtEndTime.setText(stringForTime((int)exoPlayer.getDuration()));
                    handler.postDelayed(this, 1000);
                }
            }
        });
    }

    private void toggleMediaControls() {
        if(bControlsActive){
            hideMediaController();
            bControlsActive=false;
        }
        else {
            showController();
            bControlsActive=true;
            setProgress();
        }
    }

    private void showController() {
        mediaController.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void hideMediaController() {
        mediaController.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    private void initPlayer(int position) {
        Allocator allocator = new DefaultAllocator(minBufferMs);
        DataSource dataSource = new DefaultUriDataSource(this, null, userAgent);
        ExtractorSampleSource sampleSource = new ExtractorSampleSource(
                Uri.fromFile(
                        new File(getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) + VIDEO_URI)
                ),
                dataSource, allocator, BUFFER_SEGMENT_COUNT * BUFFER_SEGMENT_SIZE);

        MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(this, sampleSource, MediaCodecSelector.DEFAULT,
                MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        MediaCodecAudioTrackRenderer audioRenderer = new MediaCodecAudioTrackRenderer(sampleSource, MediaCodecSelector.DEFAULT);
        exoPlayer = ExoPlayer.Factory.newInstance(RENDERER_COUNT);
        exoPlayer.prepare(videoRenderer, audioRenderer);
        exoPlayer.sendMessage(videoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, surfaceView.getHolder().getSurface());
        exoPlayer.seekTo(position);
        initMediaControls();
    }


    // socket stuff
    class ServerThread implements Runnable {
        public void run() {
            Socket socket = null;
            try {
                serverSocket = new ServerSocket(SERVERPORT);
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    socket = serverSocket.accept();
                    new Thread(new CommunicationThread(socket)).start();
                }
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class CommunicationThread implements Runnable {
        private Socket clientSocket;
        private BufferedReader input;
        public CommunicationThread(Socket clientSocket) {
            this.clientSocket = clientSocket;
            try {
                this.input = new BufferedReader(new InputStreamReader(this.clientSocket.getInputStream()));
            }
            catch (IOException e) {
                e.printStackTrace();
            }
        }
        public void run() {
            String read = null;
            try {
                read = input.readLine();
                updateConversationHandler.post(new updateUIThread(read));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    class updateUIThread implements Runnable {
        private String msg;
        public updateUIThread(String str) {
            this.msg = str;
        }
        @Override
        public void run() {
            System.out.println("xxx: Message: " + msg);
            if (msg.contains("seek")) {
                // TODO: validate input and  seek ranges
                exoPlayer.seekTo(Integer.parseInt(msg.split(":")[1])*1000);
            }

            else if (msg.contains("play")) {
                if(!bIsPlaying){
                    exoPlayer.setPlayWhenReady(true);
                    bIsPlaying=true;
                    setProgress();
                }
            }
            else if (msg.contains("pause")) {
                if(bIsPlaying){
                    exoPlayer.setPlayWhenReady(false);
                    bIsPlaying=false;
                }
            }
        }
    }
}
