package com.swyngmusic.swyng;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import com.spotify.sdk.android.player.Config;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.Connectivity;
import com.spotify.sdk.android.player.Error;
import com.spotify.sdk.android.player.Metadata;
import com.spotify.sdk.android.player.PlaybackState;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerEvent;
import com.spotify.sdk.android.player.Spotify;
import com.spotify.sdk.android.player.SpotifyPlayer;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Transformation;
import com.swyngmusic.swyng.interfaces.Artist;
import com.swyngmusic.swyng.interfaces.Recommendation;
import com.swyngmusic.swyng.interfaces.Track;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.List;
import java.util.Random;

public class MainActivity extends Activity implements
        Player.NotificationCallback, ConnectionStateCallback {

    //Constants
    private static final String CLIENT_ID = "4241e25175f04a0fb675959769d0c9f4";
    private static final String REDIRECT_URI = "swyngredirect://callback";
    public static final String SPOTIFY_URL_BASE = "https://api.spotify.com";
    public static final String TAG = "Swyng";
    private static final int REQUEST_CODE = 1337;
    private static String SONG_URI = "spotify:track:6KywfgRqvgvfJc3JRwaZdZ";
    private Random random = new Random();

     // UI controls which may only be enabled after the player has been initialized, (or effectively, after the user has logged in).
    private static final int[] REQUIRES_INITIALIZED_STATE = {
           R.id.play_track_button
    };
     //UI controls which should only be enabled if the player is actively playing.
    private static final int[] REQUIRES_PLAYING_STATE = {
            R.id.dislike_button,
            R.id.like_button,
            R.id.skip_song_button
    };


    //Fields
    private Boolean playInitial = true;
    private boolean checkIfClicked = false;
    private String playlistUri = null;
    private String userId = null;
    private Track nextTrack = null;
    private SpotifyPlayer mPlayer;
    private PlaybackState mCurrentPlaybackState;
    private AuthToken authToken;
    private BroadcastReceiver mNetworkStateReceiver;
    private TextView mMetadataText;
    private Metadata mMetadata;


    //Initialization
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo);
        mMetadataText = (TextView) findViewById(R.id.metadata);
        updateView();
        logStatus("Ready");
    }

    @Override
    protected void onResume() {
        super.onResume();
        mNetworkStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mPlayer != null) {
                    Connectivity connectivity = getNetworkConnectivity(getBaseContext());
                    logStatus("Network state changed: " + connectivity.toString());
                    mPlayer.setConnectivityStatus(mOperationCallback, connectivity);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mNetworkStateReceiver, filter);

        if (mPlayer != null) {
            mPlayer.addNotificationCallback(MainActivity.this);
            mPlayer.addConnectionStateCallback(MainActivity.this);
        }
    }

    //Helper
    private Connectivity getNetworkConnectivity(Context context) {
        ConnectivityManager connectivityManager;
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        if (activeNetwork != null && activeNetwork.isConnected()) {
            return Connectivity.fromNetworkType(activeNetwork.getType());
        } else {
            return Connectivity.OFFLINE;
        }
    }

    //Authentication
    private void openLoginWindow() {
        final AuthenticationRequest request = new AuthenticationRequest.Builder(CLIENT_ID, AuthenticationResponse.Type.TOKEN, REDIRECT_URI)
                .setScopes(new String[]{"user-read-private", "playlist-read", "playlist-read-private", "streaming","playlist-modify-public","playlist-modify-private"})
                .build();

        AuthenticationClient.openLoginActivity(this, REQUEST_CODE, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        // Check if result comes from the correct activity
        if (requestCode == REQUEST_CODE) {
            AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, intent);
            switch (response.getType()) {
                // Response was successful and contains auth token
                case TOKEN:
                    onAuthenticationComplete(response);
                    break;

                // Auth returned an error
                case ERROR:
                    logStatus("Auth error: " + response.getError());
                    break;

                // Most likely auth was cancelled
                default:
                    logStatus("Auth result: " + response.getType());
            }
        }
    }

    //Helper
    private void localSongHelper() {
        if(userId == null)
            userId = SpotifyFactory.getUserID(authToken);

        if(userId != null)
        {
            JSONArray playlists = SpotifyFactory.getPlaylists(authToken,userId);
            if(playlists != null && playlists.length() > 1)
            {
                try {
                    String id = playlists.getJSONObject(random.nextInt(playlists.length() - 1)).getString("id");
                    nextTrack= SpotifyFactory.getTrack(authToken,userId,id);
                }
                catch (JSONException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    private void onAuthenticationComplete(AuthenticationResponse authResponse) {
        logStatus("Got authentication token");
        authToken = new AuthToken(authResponse.getAccessToken());
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        localSongHelper();

        if (mPlayer == null) {
            Config playerConfig = new Config(getApplicationContext(), authResponse.getAccessToken(), CLIENT_ID);
            mPlayer = Spotify.getPlayer(playerConfig, this, new SpotifyPlayer.InitializationObserver() {
                @Override
                public void onInitialized(SpotifyPlayer player) {
                    logStatus("-- Player initialized --");
                    mPlayer.setConnectivityStatus(mOperationCallback, getNetworkConnectivity(MainActivity.this));
                    mPlayer.addNotificationCallback(MainActivity.this);
                    mPlayer.addConnectionStateCallback(MainActivity.this);

                    // Trigger UI refresh
                    updateView();

                    userId = SpotifyFactory.getUserID(authToken);
                    if( userId != null) {
                        playlistUri = SpotifyFactory.createPlaylist(authToken, userId, "Team Name Here Playlist");
                    }
                }

                @Override
                public void onError(Throwable error) {
                    logStatus("Error in initialization: " + error.getMessage());
                }
            });
        } else {
            mPlayer.login(authResponse.getAccessToken());
        }
    }

    //UI Events
    public void onLoginButtonClicked(View view) {
        if (!isLoggedIn()) {
            logStatus("Logging in");
            openLoginWindow();
        } else {
            mPlayer.logout();
        }
    }

    public void onPlayButtonClicked(View view) {
        if (playInitial) {
            mPlayer.playUri(mOperationCallback, nextTrack.getUri(), 0, 0);
            for (int id : REQUIRES_PLAYING_STATE) {
                findViewById(id).setEnabled(true);
            }
            playInitial = false;
            updateView();
        }
        else {
            onPauseButtonClicked(view);
        }
    }

    public void onPauseButtonClicked(View view) {
        if(!checkIfClicked)
            mMetadataText.setVisibility(View.INVISIBLE);
        if (mCurrentPlaybackState != null && mCurrentPlaybackState.isPlaying) {
            mPlayer.pause(mOperationCallback);
            Button playButton = (Button) findViewById(R.id.play_track_button);
            playButton.setText(R.string.resume_button_label_toggle);

        } else {
            mPlayer.resume(mOperationCallback);
            Button playButton = (Button) findViewById(R.id.play_track_button);
            playButton.setText(R.string.pause_button_label_toggle);
        }
    }

    public void onDislikeButtonClicked(View view) {
        if(!checkIfClicked)
            mMetadataText.setVisibility(View.INVISIBLE);
        List<Artist> artists = nextTrack.getArtists();
        if (artists.size() > 0) {
            String artistID = parseIDfromURI(artists.get(0).getUri());
            String trackID = parseIDfromURI(nextTrack.getUri());

            createQueue(artistID, trackID);
            mPlayer.playUri(mOperationCallback, nextTrack.getUri(), 0, 0);
        }
    }

    public void onSkipSongButtonClicked(View view) {
        if(!checkIfClicked)
            mMetadataText.setVisibility(View.INVISIBLE);

        mPlayer.skipToNext(mOperationCallback);
    }

    public void onLikeButtonClicked(View view) {
        mMetadataText.setVisibility(View.VISIBLE);
        String artistID = parseIDfromURI(mMetadata.currentTrack.artistUri);
        String trackID = parseIDfromURI(mMetadata.currentTrack.uri);

        if(playlistUri != null && userId != null)
            SpotifyFactory.addTrackToPlaylist(authToken,userId,parseIDfromURI(playlistUri),mMetadata.currentTrack.uri);

        createQueue(artistID, trackID);
    }

    //Callback Methods
    @Override
    public void onLoggedIn() {
        logStatus("Login complete");
        updateView();
    }

    @Override
    public void onLoggedOut() {
        playInitial = true;
        Button playButton = (Button) findViewById(R.id.play_track_button);
        playButton.setText(R.string.play_track_button_label);
        logStatus("Logout complete");
        updateView();
    }

    public void onLoginFailed(int error) {
        logStatus("Login error "+ error);
    }

    @Override
    public void onTemporaryError() {
        logStatus("Temporary error occurred");
    }

    @Override
    public void onConnectionMessage(final String message) {
        logStatus("Incoming connection message: " + message);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mNetworkStateReceiver);

        if (mPlayer != null) {
            mPlayer.removeNotificationCallback(MainActivity.this);
            mPlayer.removeConnectionStateCallback(MainActivity.this);
        }
    }

    @Override
    public void onPlaybackEvent(PlayerEvent event) {
        logStatus("Event: " + event);
        mCurrentPlaybackState = mPlayer.getPlaybackState();
        mMetadata = mPlayer.getMetadata();
        Log.i(TAG, "Player state: " + mCurrentPlaybackState);
        Log.i(TAG, "Metadata: " + mMetadata);
        updateView();
    }

    //Error Handling
    private void logStatus(String status) {
        Log.i(TAG, status);
    }

    @Override
    public void onPlaybackError(Error error) {
        logStatus("Err: " + error);
    }

    private final Player.OperationCallback mOperationCallback = new Player.OperationCallback() {
        @Override
        public void onSuccess() {
            logStatus("OK!");
        }

        @Override
        public void onError(Error error) {
            logStatus("ERROR:" + error);
        }
    };

    //Destruction
    @Override
    protected void onDestroy() {
        Spotify.destroyPlayer(this);
        super.onDestroy();
    }

    //Helper Functions
    private static String parseIDfromURI(String uri)
    {
        String[] args = uri.split(":");
        return args[args.length-1];
    }

    public void createQueue(String artistID, String trackID) {
        Recommendation rec = SpotifyFactory.getRecommendation(authToken, artistID, "", trackID);
        List<Track> tracks = rec.getTracks();
        if(tracks != null && tracks.size() > 0)
        {
            nextTrack = tracks.get(random.nextInt(tracks.size()-1));

            if(nextTrack != null) {
                int size = (5 < tracks.size()) ? 5 : tracks.size();
                for (int i = 0; i < size; i++) {
                    mPlayer.queue(mOperationCallback, tracks.get(i).getUri());
                }
            }
        }
    }

    private boolean isLoggedIn() {
        return mPlayer != null && mPlayer.isLoggedIn();
    }

    private void updateView() {
        boolean loggedIn = isLoggedIn();
        Button loginButton = (Button) findViewById(R.id.login_button);
        loginButton.setText(loggedIn ? R.string.logout_button_label : R.string.login_button_label);


        for (int id : REQUIRES_INITIALIZED_STATE) {
            findViewById(id).setEnabled(loggedIn);
        }

        if (mPlayer != null) {
            mCurrentPlaybackState = mPlayer.getPlaybackState();
            mMetadata = mPlayer.getMetadata();
        }

        boolean playing = loggedIn && mMetadata != null;
        for (int id : REQUIRES_PLAYING_STATE) {
            findViewById(id).setEnabled(playing);
        }

        if (mMetadata != null) {
            findViewById(R.id.like_button).setVisibility(View.VISIBLE);
            findViewById(R.id.dislike_button).setVisibility(View.VISIBLE);
        }

        final ImageView coverArtView = (ImageView) findViewById(R.id.cover_art);

        if (mMetadata != null && mMetadata.currentTrack != null) {
            final double durationStr = mMetadata.currentTrack.durationMs;
            int minutes = (int)durationStr / (60 * 1000);
            int seconds = ((int)durationStr / 1000) % 60;
            String time = String.format("%d:%02d", minutes, seconds);
            mMetadataText.setText(mMetadata.currentTrack.name +  " - " + mMetadata.currentTrack.artistName + " " + time);

            Picasso.with(this)
                    .load(mMetadata.currentTrack.albumCoverWebUrl)
                    .transform(new Transformation() {
                        @Override
                        public Bitmap transform(Bitmap source) {
                            //Darkens Bitmap
                            final Bitmap copy = source.copy(source.getConfig(), true);
                            source.recycle();
                            final Canvas canvas = new Canvas(copy);
                            canvas.drawColor(0xbb000000);
                            return copy;
                        }

                        @Override
                        public String key() {
                            return "darken";
                        }
                    })
                    .into(coverArtView);
        } else {
            coverArtView.setBackground(null);
        }

    }


}