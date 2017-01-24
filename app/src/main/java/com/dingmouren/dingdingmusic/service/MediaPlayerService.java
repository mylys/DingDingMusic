package com.dingmouren.dingdingmusic.service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.dingmouren.dingdingmusic.Constant;
import com.dingmouren.dingdingmusic.MyApplication;
import com.dingmouren.dingdingmusic.bean.LocalMusicBean;
import com.dingmouren.dingdingmusic.notification.MusicNotification;
import com.jiongbull.jlog.JLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

/**
 * Created by dingmouren on 2017/1/18.
 */

public class MediaPlayerService extends Service implements OnPreparedListener, OnCompletionListener, OnErrorListener {
    private static final String TAG = MediaPlayerService.class.getName();
    //音乐列表
    private List<LocalMusicBean> musicsList = new ArrayList<>();
    private int musicsListSize;
    //通知栏
    private MusicNotification musicNotification;
    private LocalMusicBean bean;
    //MediaPlayer
    private MediaPlayer mediaPlayer;
    private int currentTime = 0;//记录当前播放时间
    //广播接收者
    private MusicBroadCast musicBroadCast;
    //来自通知栏的action
    private final String MUSIC_NOTIFICATION_ACTION_PLAY = "musicnotificaion.To.PLAY";
    private final String MUSIC_NOTIFICATION_ACTION_NEXT = "musicnotificaion.To.NEXT";
    private final String MUSIC_NOTIFICATION_ACTION_CLOSE = "musicnotificaion.To.CLOSE";
    // 来自MusicService的Action
    private final String MUSIC_ACTIVITY_SERVICE_ACTION = "activity.to.musicservice";
    //播放的位置
    private int position = 0;
    //PlayingActivity的Messenger对象
    private Messenger mMessengerPlayingActivity;
    //LocalMusicActivity的Messenger对象
    private Messenger mMessengerLocalMusicActivity;
    //播放模式的变量
    private int play_mode = Constant.MEDIA_PLAYER_PLAY_ALL;//默认是循环播放
    @Override
    public void onCreate() {
        super.onCreate();
        Log.e(TAG, "onCreate");
        //初始化通知栏
        musicNotification = MusicNotification.getMusicNotification();
        musicNotification.setManager((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE));
        musicNotification.onCreateMusicNotification();
        //初始化MediaPlayer,设置监听事件
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
        //注册广播,用于跟通知栏进行通信
        musicBroadCast = new MusicBroadCast();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MUSIC_NOTIFICATION_ACTION_PLAY);
        filter.addAction(MUSIC_NOTIFICATION_ACTION_NEXT);
        filter.addAction(MUSIC_NOTIFICATION_ACTION_CLOSE);
        registerReceiver(musicBroadCast, filter);


    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JLog.e(TAG, "onStartCommand");
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        JLog.e(TAG, "onBind");
        return mServiceMessenger.getBinder();
    }


    @Override
    public boolean onUnbind(Intent intent) {
       JLog.e(TAG, "onUnbind");
        return super.onUnbind(intent);
    }


    @Override
    public void onDestroy() {
        JLog.e(TAG, "onDestroy");
        //释放资源
        if (null != mediaPlayer) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        //取消注册的广播
        if (null != musicBroadCast) unregisterReceiver(musicBroadCast);

        //取消通知
        if (null != musicNotification) musicNotification.onCancelMusicNotification();

        super.onDestroy();
    }

    Messenger mServiceMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msgFromClient) {
            switch (msgFromClient.what) {
                case Constant.PLAYING_ACTIVITY:
                    mMessengerPlayingActivity = msgFromClient.replyTo;
                    JLog.e(TAG,"PlayingActivity初始化");
                    //将现在播放的歌曲发送给PlayingActivity
                    updateSongName();
                    break;
                case Constant.PLAYING_ACTIVITY_PLAY:
                    playSong();
                    break;
                case Constant.PLAYING_ACTIVITY_PRE:
                    preSong();
                    break;
                case Constant.PLAYING_ACTIVITY_NEXT:
                    nextSong();
                    break;
                case Constant.PLAYING_ACTIVITY_SINGLE://是否单曲循环
                    if (mediaPlayer.isLooping()){
                        mediaPlayer.setLooping(false);
                        play_mode = Constant.MEDIA_PLAYER_PLAY_ALL;
                    }else {
                        mediaPlayer.setLooping(true);
                        play_mode = Constant.MEDIA_PLAYER_PLAY_SINGLEONE;
                    }
                    break;
                case Constant.PLAYING_ACTIVITY_CUSTOM_PROGRESS://在用户拖动进度条的位置播放
                    int percent = msgFromClient.arg1;
                    currentTime = percent * mediaPlayer.getDuration() / 100;
                    mediaPlayer.seekTo(currentTime);
                    break;
                case Constant.PLAYING_ACTIVITY_DATA://接受PlayingActivity传递过来的歌曲的集合数据
                    Bundle songsData = msgFromClient.getData();
                    musicsList.clear();
                    if (0 == musicsList.size()) {//当歌曲集合没有数据的时候
                        musicsList.addAll((List<LocalMusicBean>) songsData.getSerializable(Constant.PLAYING_ACTIVITY_DATA_KEY));
                    }
                    if (null != musicsList) musicsListSize = musicsList.size();
                    if (null != musicsList) {
                        JLog.e(TAG, "musicsList--" + musicsList.toString());
                        JLog.e(TAG, "musicsListSize--" + musicsListSize);
                        JLog.e(TAG, "接收到来自PlayingActivity客户端的数据"  );
                    }
                    break;
                case Constant.LOCAL_MUSIC_ACTIVITY:
                    mMessengerLocalMusicActivity = msgFromClient.replyTo;
                    musicsList.clear();
                    if (0 == musicsList.size()) {//LocalMusicActivity连接上服务的时候，将本地歌曲集合数据导入
                        musicsList.addAll(MyApplication.getDaoSession().getLocalMusicBeanDao().loadAll());
                    }
                    if (null != musicsList) musicsListSize = musicsList.size();
                    updateSongPosition();
                    break;
                case Constant.LOCAL_MUSIC_ACTIVITY_PLAY://播放本地音乐列表被点击的歌曲
                    playCustomSong(msgFromClient.arg1);
                    break;

            }
            super.handleMessage(msgFromClient);
        }
    });



    /**
     * 音乐播放
     *
     * @param musicUrl
     */
    private void play(String musicUrl) {
        JLog.e(TAG,"play(String musicUrl)--musicUrl" + musicUrl);
        if (null == mediaPlayer) return;
        mediaPlayer.reset();//停止音乐后，不重置的话就会崩溃
        try {
            mediaPlayer.setDataSource(getApplicationContext(), Uri.parse(musicUrl));
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 音乐暂停
     */
    private void pause() {
        JLog.e(TAG, "pause()");
        if (null == mediaPlayer) return;
        if (mediaPlayer.isPlaying()) {
            currentTime = mediaPlayer.getCurrentPosition();
            mediaPlayer.pause();
            sendIsPlayingMsg();//发送播放器是否在播放的状态
        }
        musicNotification.onUpdateMusicNotification(bean, mediaPlayer.isPlaying());
    }

    /**
     * 音乐继续播放
     */
    public void resume() {
        JLog.e(TAG, "resume()");
        if (null == mediaPlayer) return;
        mediaPlayer.start();
        sendIsPlayingMsg();//发送播放器是否在播放的状态
        //播放的同时，更新进度条
        updateSeekBarProgress(mediaPlayer.isPlaying());
        //将现在播放的歌曲发送给PlayingActivity
        updateSongName();
        //将现在播放的歌曲发送给LocalMusicActivity
        updateSongPosition();
        if (currentTime > 0) {
            mediaPlayer.seekTo(currentTime);
        }
        musicNotification.onUpdateMusicNotification(bean, mediaPlayer.isPlaying());
    }

    /**
     * 停止音乐
     */
    private void stop() {
        JLog.e(TAG, "stop()");
        if (null == mediaPlayer) return;
        mediaPlayer.stop();
        currentTime = 0;//停止音乐，将当前播放时间置为0
    }
    //--------------监听listener
    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        Log.e(TAG, "onCompletion");
        if (play_mode != Constant.MEDIA_PLAYER_PLAY_SINGLEONE ){
            Message msgToServiceNext = Message.obtain();
            msgToServiceNext.what = Constant.PLAYING_ACTIVITY_NEXT;
            try {
                mServiceMessenger.send(msgToServiceNext);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int i, int i1) {
        JLog.e(TAG, "onError--i:" + i + "  i1:" + i1);
        return false;
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        JLog.e(TAG, "onPrepared");
        //准备加载的时候
        resume();
    }


    /**
     * 播放
     */
    private void playSong() {
        JLog.e(TAG, "playSong()");
        if (null == musicsList && 0 == musicsList.size()) return;
        bean = musicsList.get(position);
        if (mediaPlayer.isPlaying()) {//如果是正在播放状态的话，就暂停
            pause();
        } else {
            if (currentTime > 0) {//currentTime>0说明当前是暂停状态，直接播放
                resume();
            } else {
                if (null != bean) {
                    play(bean.getPath());
                    //每当从头开始播放一首歌曲时，每秒发送一条播放进度的消息，
                    if (null != mServiceMessenger) {
                        Message msgFromServiceProgress = Message.obtain();
                        msgFromServiceProgress.what = Constant.MEDIA_PLAYER_SERVICE_PROGRESS;
                        try {
                            mServiceMessenger.send(msgFromServiceProgress);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

    }



    /**
     * 下一首
     */
    private void nextSong() {
        JLog.e(TAG, "nextSong()");
        currentTime = 0;
        if (position < 0) {
            position = 0;
        }
        if (musicsListSize > 0) {
            position++;
            if (position < musicsListSize) {//当前歌曲的索引小于歌曲集合的长度
                bean = musicsList.get(position);
                play(bean.getPath());
            } else {
                bean = musicsList.get(0);//超过长度时，就播放第一首
                play(bean.getPath());
            }
        }
    }

    /**
     * 上一首
     */
    private void preSong() {
        JLog.e(TAG, "preSong()");
        currentTime = 0;
        if (position < 0) {
            position = 0;
        }
        if (musicsListSize > 0) {
            position--;
            if (position >= 0) {//大于等于0的情况
                bean = musicsList.get(position);
                play(bean.getPath());
            } else {
                bean = musicsList.get(0);//小于0时，播放第一首歌
                play(bean.getPath());
            }
        }
    }


    /**
     * 更新进度条进度，通过发送消息的方式
     * @param playing
     */
    private void updateSeekBarProgress(boolean playing) {
        JLog.e(TAG,"updateSeekBarProgress更新进度条");
        Observable.interval(1,1, TimeUnit.SECONDS, Schedulers.computation()).subscribe(new Action1<Long>() {
            @Override
            public void call(Long aLong) {
                sendUpdateProgressMsg();
            }
        });
    }

    //发送更新进度的消息
    private void sendUpdateProgressMsg(){
        if (null != mediaPlayer && mediaPlayer.isPlaying()) {
            Message msgToPlayingAcitvity = Message.obtain();
            msgToPlayingAcitvity.what = Constant.MEDIA_PLAYER_SERVICE_PROGRESS;
            msgToPlayingAcitvity.arg1 = mediaPlayer.getCurrentPosition();
            msgToPlayingAcitvity.arg2 = mediaPlayer.getDuration();
//            JLog.e(TAG, "发给客户端的时间--getCurrentPosition:" + mediaPlayer.getCurrentPosition() + " getDuration" + mediaPlayer.getDuration());
            try {
                if (null != mMessengerPlayingActivity) {
                    mMessengerPlayingActivity.send(msgToPlayingAcitvity);
//                    Log.e(TAG, "发消息了");
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 将正在播放的歌曲名称发送给PlayingActivity
     */
    private void  updateSongName(){
        if (null != mMessengerPlayingActivity && null != this.bean) {
            Message msgToCLient = Message.obtain();
            Bundle bundle = new Bundle();
            bundle.putSerializable(Constant.MEDIA_PLAYER_SERVICE_MODEL_PLAYING,this.bean);
            msgToCLient.setData(bundle);
            msgToCLient.what = Constant.MEDIA_PLAYER_SERVICE_SONG_PLAYING;
            try {
                mMessengerPlayingActivity.send(msgToCLient);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 将正在播放的歌曲的position发送给LocalMusicActivity
     */
    private void updateSongPosition(){
        if (null != mMessengerLocalMusicActivity && null != this.bean){
            Message msgToCLient = Message.obtain();
            Bundle bundle = new Bundle();
            bundle.putSerializable(Constant.MEDIA_PLAYER_SERVICE_MODEL_PLAYING,this.bean);
            msgToCLient.setData(bundle);
            msgToCLient.what = Constant.MEDIA_PLAYER_SERVICE_SONG_PLAYING;
            try {
                mMessengerLocalMusicActivity.send(msgToCLient);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 播放本地音乐列表中被点击的歌曲
     * @param position
     */
    private void playCustomSong(int position){
        this.currentTime = 0;
        this.position = position;
        bean = musicsList.get(position);
        if (null != bean){
            play(bean.getPath());

        }
    }

    /**
     * 发送播放器是否在播放的状态，更新PlayingActivity的UI
     */
    private void sendIsPlayingMsg(){
        if (null != mServiceMessenger) {
            Message msgToClient = Message.obtain();
            msgToClient.arg1 = mediaPlayer.isPlaying() ? 1 : 0;
            msgToClient.what = Constant.MEDIA_PLAYER_SERVICE_IS_PLAYING;
            try {
                mMessengerPlayingActivity.send(msgToClient);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * 广播接收者:与通知栏进行通信，通知栏控制歌曲的播放、下一首取、取消通知栏（停止音乐播放）
     */
    private class MusicBroadCast extends BroadcastReceiver {
        private final String TAG_BRAODCAST = MusicBroadCast.class.getName();
        private int valueFromNotification = 0;//来自通知的extra中的值

        @Override
        public void onReceive(Context context, Intent intent) {
            //MusicNotification的控制
            valueFromNotification = intent.getIntExtra("type", -1);
            if (valueFromNotification > 0) {
                musicNotificationService(valueFromNotification);
            }

        }


        /**
         * 来自通知的控制
         *
         * @param value
         */
        private void musicNotificationService(int value) {
            Log.e(TAG_BRAODCAST, "musicNotificationService");
            switch (value) {
                case 30001:
                    playSong(); //播放
                    break;
                case 30002:
                    nextSong();//下一首
                    break;
                case 30003:
                    musicNotification.onCancelMusicNotification();//关闭通知栏
                    stop();//停止音乐
                    break;
            }
        }

    }

}