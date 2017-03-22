package io.github.varunj.sangoshthi_receiver;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;

import org.apache.commons.lang3.SerializationUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Formatter;
import java.util.Locale;

/**
 * Created by Varun on 04-03-2017.
 */

public class GroupVideoActivity extends AppCompatActivity {
    private String showName, senderPhoneNum;
    Thread subscribeThread;
    Thread subscribeNormalThread;
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
    private boolean bAutoplay=false;
    private boolean bIsPlaying=false;
    private boolean bControlsActive=true;
    private int RENDERER_COUNT = 300000;
    private int minBufferMs =    250000;
    private final int BUFFER_SEGMENT_SIZE = 64 * 1024;
    private final int BUFFER_SEGMENT_COUNT = 256;
    private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:40.0) Gecko/20100101 Firefox/40.0";
    private String VIDEO_URI = "/";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.video_player_layout);

        // Hide the status bar.
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);

        // get groupName and senderPhoneNumber
        Intent i = getIntent();
        showName = i.getStringExtra("showName");
        VIDEO_URI = "/" + i.getStringExtra("videoname");
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        senderPhoneNum = pref.getString("phoneNum", "0000000000");

        // AMQP stuff
        AMQPPublish.setupConnectionFactory();
        AMQPPublish.publishToAMQP();
        setupConnectionFactory();
        subscribe();
        subscribeNormal();

        // Video Player Stuff
        setContentView(R.layout.video_player_layout);
        surfaceView = (SurfaceView) findViewById(R.id.sv_player);
        mediaController = (LinearLayout) findViewById(R.id.lin_media_controller);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initPlayer(0);
        if(bAutoplay){
            if(exoPlayer!=null){
                exoPlayer.setPlayWhenReady(true);
                bIsPlaying=true;
                setProgress();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (AMQPPublish.publishThread != null)
            AMQPPublish.publishThread.interrupt();
        if (subscribeThread != null)
            subscribeThread.interrupt();
        if (subscribeNormalThread != null)
            subscribeNormalThread.interrupt();
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Closing Activity")
                .setMessage("Sure you don't want to continue?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setNegativeButton("No", null)
                .show();
        if (AMQPPublish.publishThread != null)
            AMQPPublish.publishThread.interrupt();
        if (subscribeThread != null)
            subscribeThread.interrupt();
        if (subscribeNormalThread != null)
            subscribeNormalThread.interrupt();
    }

    void publishMessage(Long location, String message) {
        try {
            final JSONObject jsonObject = new JSONObject();
            jsonObject.put("objective", message);
            jsonObject.put("sender", senderPhoneNum);
            jsonObject.put("timestamp", DateFormat.getDateTimeInstance().format(new Date()));
            jsonObject.put("show_name", showName);
            jsonObject.put("location", location);
            AMQPPublish.queue.putLast(jsonObject);
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static ConnectionFactory factory = new ConnectionFactory();
    public static  void setupConnectionFactory() {
        try {
            factory.setUsername(StarterActivity.SERVER_USERNAME);
            factory.setPassword(StarterActivity.SERVER_PASS);
            factory.setHost(StarterActivity.IP_ADDR);
            factory.setPort(StarterActivity.SERVER_PORT);
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(10000);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
    void subscribe() {
        subscribeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        // xxx: read http://www.rabbitmq.com/tutorials/tutorial-three-python.html, http://stackoverflow.com/questions/10620976/rabbitmq-amqp-single-queue-multiple-consumers-for-same-message
                        AMQP.Queue.DeclareOk queue = channel.queueDeclare();
                        channel.queueBind(queue.getQueue(), "amq.fanout", showName);
                        QueueingConsumer consumer = new QueueingConsumer(channel);
                        channel.basicConsume(queue.getQueue(), true, consumer);
                        while (true) {
                            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                            final JSONObject message = new JSONObject(new String(delivery.getBody()));
                            displayMessage(message);
                            if (message.getString("show_name").equals(showName)) {
                                if (message.getString("message").contains("seek")) {
                                    exoPlayer.seekTo(Integer.parseInt(message.getString("message").split(":")[1]));
                                }
                                else if (message.getString("message").contains("play")) {
                                    if(!bIsPlaying){
                                        exoPlayer.seekTo(Integer.parseInt(message.getString("message").split(":")[1]));
                                        exoPlayer.setPlayWhenReady(true);
                                        bIsPlaying=true;
                                        setProgress();
                                    }
                                }
                                else if (message.getString("message").contains("pause")) {
                                    if(bIsPlaying){
                                        exoPlayer.seekTo(Integer.parseInt(message.getString("message").split(":")[1]));
                                        exoPlayer.setPlayWhenReady(false);
                                        bIsPlaying=false;
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e1) {
                        e1.printStackTrace();
                        try {
                            Thread.sleep(4000); //sleep and then try again
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                }
            }
        });
        subscribeThread.start();
    }

    void subscribeNormal() {
        subscribeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        // xxx: read http://www.rabbitmq.com/tutorials/tutorial-three-python.html, http://stackoverflow.com/questions/10620976/rabbitmq-amqp-single-queue-multiple-consumers-for-same-message
                        channel.queueDeclare("server_to_broadcaster", false, false, false, null);
                        QueueingConsumer consumer = new QueueingConsumer(channel);
                        channel.basicConsume("server_to_asha", true, consumer);
                        while (true) {
                            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                            final JSONObject message = new JSONObject(new String(delivery.getBody()));
                            try {
                                System.out.println("xxx:" + " " + message.getString("objective") + ":" +
                                        message.getString("sender") + "->" + message.getString("location") +
                                        " " + message.getString("show_name"));
                            } catch (JSONException e) {
                                e.printStackTrace();
                            }

                            if (message.getString("objective").equals("control_show_flush")) {
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        final ImageButton btnQuery = (ImageButton) findViewById(R.id.btnQuery);
                                        btnQuery.setEnabled(true);
                                    }
                                });

                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e1) {
                        e1.printStackTrace();
                        try {
                            Thread.sleep(4000); //sleep and then try again
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            break;
                        }
                    }
                }
            }
        });
        subscribeThread.start();
    }

    public static void displayMessage(JSONObject message) {
        try {
            System.out.println("xxx:" + " " + message.getString("objective") + ":" +
                    message.getString("broadcaster") + "->" + message.getString("show_name") +
                    " " + message.getString("message") + "@" + message.getString("timestamp"));
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // initialising media control
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
                publishMessage(exoPlayer.getCurrentPosition(), "like");
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
                publishMessage(exoPlayer.getCurrentPosition(), "query");
                btnQuery.setEnabled(false);
            }
        });
    }

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
                         new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),"/Sangoshthi_Receiver/" + VIDEO_URI)
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
}
