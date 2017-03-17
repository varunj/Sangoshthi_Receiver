package io.github.varunj.sangoshthi_receiver;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.util.Date;
import java.util.Formatter;
import java.util.Locale;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import io.github.varunj.sangoshthi_broadcaster.Message;

/**
 * Created by Varun on 04-03-2017.
 */

public class GroupVideoActivity extends AppCompatActivity {
    private String receiverGroupName , senderPhoneNum;
    private Message message1;
    Thread subscribeThread;
    Thread publishThread;

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
    private String VIDEO_URI = "/video.mp4";

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
        receiverGroupName = i.getStringExtra("groupName");
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        senderPhoneNum = pref.getString("phoneNum", "0000000000");

        // AMQP stuff
        setupConnectionFactory();
        publishToAMQP();
        subscribe();

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
        publishThread.interrupt();
        subscribeThread.interrupt();
    }

    ConnectionFactory factory = new ConnectionFactory();
    private void setupConnectionFactory() {
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

    public void publishToAMQP() {
        publishThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(true) {
                    try {
                        Connection connection = factory.newConnection();
                        Channel channel = connection.createChannel();
                        channel.confirmSelect();
                        while (true) {
                            message1 = queue.takeFirst();
                            try {
                                // xxx: read http://www.rabbitmq.com/api-guide.html. Set QueueName=RoutingKey to send message to only 1 queue
                                channel.exchangeDeclare("defaultExchangeName", "direct", true);
                                channel.queueDeclare(message1.getReceiver(), true, false, false, null);
                                channel.queueBind(message1.getReceiver(), "defaultExchangeName", message1.getReceiver());
                                // send message1
                                channel.basicPublish("defaultExchangeName", message1.getReceiver(), null, SerializationUtils.serialize(message1));

                                displayMessage(message1, 1);
                                channel.waitForConfirmsOrDie();
                            } catch (Exception e) {
                                queue.putFirst(message1);
                                throw e;
                            }
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        e.printStackTrace();
                        try {
                            Thread.sleep(5000); //sleep and then try again
                        } catch (InterruptedException e1) {
                            break;
                        }
                    }
                }
            }
        });
        publishThread.start();
    }

    private BlockingDeque<Message> queue = new LinkedBlockingDeque<Message>();
    void publishMessage(String message) {
        try {
            Message chatMessage = new Message();
            chatMessage.setMessage(message);
            chatMessage.setTimestamp(DateFormat.getDateTimeInstance().format(new Date()));
            chatMessage.setSender(senderPhoneNum);
            // xxx: automate this
            chatMessage.setReciever("LikeQueryButton");
            queue.putLast(chatMessage);
        } catch (InterruptedException e) {
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
                        channel.queueBind(queue.getQueue(), "amq.fanout", receiverGroupName);
                        QueueingConsumer consumer = new QueueingConsumer(channel);
                        channel.basicConsume(queue.getQueue(), true, consumer);

                        while (true) {
                            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
                            final Message message = (Message)SerializationUtils.deserialize(delivery.getBody());

                            displayMessage(message, 2);

                            if (message.getReceiver().equals(receiverGroupName)) {
                                if (message.getMessage().contains("seek")) {
                                    exoPlayer.seekTo(Integer.parseInt(message.getMessage().split(":")[1]));
                                }
                                else if (message.getMessage().contains("play")) {
                                    if(!bIsPlaying){
                                        exoPlayer.seekTo(Integer.parseInt(message.getMessage().split(":")[1]));
                                        exoPlayer.setPlayWhenReady(true);
                                        bIsPlaying=true;
                                        setProgress();
                                    }
                                }
                                else if (message.getMessage().contains("pause")) {
                                    if(bIsPlaying){
                                        exoPlayer.seekTo(Integer.parseInt(message.getMessage().split(":")[1]));
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

    public void displayMessage(Message message, int x) {
        System.out.println("xxx:" + x + "   " + message.getSender() + "->" + message.getReceiver() + "   " + message.getMessage() + "   " + message.getTimestamp());
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
                publishMessage("user:" + senderPhoneNum + ",video:" + VIDEO_URI
                        + ",pos:" + exoPlayer.getCurrentPosition() + ",action:like,");
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
                publishMessage("user:" + senderPhoneNum + ",video:" + VIDEO_URI
                        + ",pos:" + exoPlayer.getCurrentPosition() + ",action:query,");
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
