package it.geosolutions.savemybike.ui.activity;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Fragment;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDelegate;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.amazonaws.mobileconnectors.cognitoidentityprovider.tokens.CognitoIdToken;

import java.lang.ref.WeakReference;

import butterknife.BindView;
import butterknife.ButterKnife;
import it.geosolutions.savemybike.BuildConfig;
import it.geosolutions.savemybike.R;
import it.geosolutions.savemybike.data.Constants;
import it.geosolutions.savemybike.data.Util;
import it.geosolutions.savemybike.data.server.RetrofitClient;
import it.geosolutions.savemybike.data.server.S3Manager;
import it.geosolutions.savemybike.data.service.SaveMyBikeService;
import it.geosolutions.savemybike.model.Configuration;
import it.geosolutions.savemybike.model.Session;
import it.geosolutions.savemybike.model.Vehicle;
import it.geosolutions.savemybike.ui.fragment.BikeListFragment;
import it.geosolutions.savemybike.ui.fragment.LoginFragment;
import it.geosolutions.savemybike.ui.fragment.RecordFragment;
import it.geosolutions.savemybike.ui.fragment.StatsFragment;

/**
 * Created by Robert Oehler on 25.10.17.
 *
 * Main activity of the SaveMyBike app
 */
public class SaveMyBikeActivity extends AppCompatActivity {

    private final static String TAG = "SaveMyBikeActivity";

    private final static int UI_UPDATE_INTERVAL = 1000;

    private SaveMyBikeService mService;

    private Configuration configuration;
    private Vehicle currentVehicle;
    private boolean applyServiceVehicle = false;

    protected static final byte PERMISSION_REQUEST = 122;
    private Handler handler;
    private MReceiver mReceiver;

    public enum PermissionIntent {
        LOCATION,
        SD_CARD
    }

    protected PermissionIntent mPermissionIntent;

    private boolean simulate = false;
    private boolean uploadWithWifiOnly = true;

    @BindView(R.id.navigation) BottomNavigationView navigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);

        //inflate
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);

        navigation.setOnNavigationItemSelectedListener(mOnNavigationItemSelectedListener);

        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        final String idTokenString = preferences.getString(Constants.PREF_CONFIG_IDTOKEN, null);
        if(idTokenString == null){
            // login
            showLoginFragment();
        }else {
            CognitoIdToken cidt = new CognitoIdToken(idTokenString);
            if(System.currentTimeMillis() > cidt.getExpiration().getTime()){
                showLoginFragment();
            }else {
                //select the "record" fragment
                changeFragment(0);
            }
        }

        //load the configuration and select the current vehicle
        this.currentVehicle = getCurrentVehicleFromConfig();

        //when online, update the config from remote
        if (Util.isOnline(getBaseContext())) {

            new GetRemoteConfigTask(getBaseContext(), new RetrofitClient.GetConfigCallback() {
                @Override
                public void gotConfig(final Configuration configuration) {

                    if (configuration != null) {

                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "config downloaded : " + configuration.id);
                        }

                        //save the config
                        Configuration.saveConfiguration(getBaseContext(), configuration);

                        //update model
                        SaveMyBikeActivity.this.configuration = configuration;
                        SaveMyBikeActivity.this.currentVehicle = getCurrentVehicleFromConfig();

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                //invalidate UI
                                invalidateRecordingUI();
                            }
                        });

                    } else {
                        Log.e(TAG, "error downloading config ");
                    }
                }

                @Override
                public void error(String message) {
                    Log.e(TAG, "error downloading config " + message);
                }
            }).execute();
        } else {
            //local config is used
        }

        /*
         * Check if data can be uploaded
         *
         *   //for the upload we need the permission to write to the sd card
         *  TODO we may give an explanation for what the SD card access is necessary
         *  TODO we may ask the user for upload permission and only then check this sd-permission
         */
        this.uploadWithWifiOnly = preferences.getBoolean(Constants.PREF_WIFI_ONLY_UPLOAD, Constants.DEFAULT_WIFI_ONLY);

        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && permissionNecessary(Manifest.permission.WRITE_EXTERNAL_STORAGE, PermissionIntent.SD_CARD))) {

            final S3Manager s3Manager = new S3Manager(getBaseContext(), uploadWithWifiOnly);
            s3Manager.checkUpload();
        }
    }

    void showLoginFragment(){
        changeFragment(3);
        navigation.setVisibility(View.GONE);
        getSupportActionBar().hide();
    }

    @Override
    protected void onStart() {
        super.onStart();

        //check if a session is active, if so bind to it
        final boolean isServiceRunning = isServiceRunning(getBaseContext(), Constants.SERVICE_NAME);
        if (isServiceRunning) {
            bindToService(new Intent(this, SaveMyBikeService.class));
            applyServiceVehicle = true;
        }
        //start updating the UI
        getHandler().removeCallbacks(mUpdateUITask);
        getHandler().postDelayed(mUpdateUITask, 10);

        //register receiver
        registerReceiver(getReceiver(), new IntentFilter(Constants.INTENT_STOP_FROM_SERVICE));
        registerReceiver(getReceiver(), new IntentFilter(Constants.INTENT_VEHICLE_UPDATE));

        //when not having an ongoing session, invalidate with the local vehicle
        if (!applyServiceVehicle) {
            invalidateRecordingUI();
        } else {
            //otherwise the UI update is done when re-binding to the service in @link onServiceConnected()
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        //unbind the UI from the service
        if (mService != null) {
            unbindService(mServiceConnection);
        }
        //stop updating the UI
        getHandler().removeCallbacks(mUpdateUITask);

        //unregister receiver
        unregisterReceiver(getReceiver());
    }

    /**
     * invalidates the UI of the recording fragment if it is currently visible
     */
    private void invalidateRecordingUI() {
        Fragment currentFragment = getCurrentFragment();
        if (currentFragment != null && currentFragment instanceof RecordFragment) {
            ((RecordFragment) currentFragment).invalidateUI(currentVehicle);
        }
    }

    /**
     * starts the recording of a session by launching the recording service and binding to it
     */
    public void startRecording() {

        //check location permission
        if (!(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && permissionNecessary(Manifest.permission.ACCESS_FINE_LOCATION, PermissionIntent.LOCATION))) {

            //start service using bindService
            Intent serviceIntent = new Intent(this, SaveMyBikeService.class);

            //TODO in a future update a session could be continued, pass the session id here
            //passing -1 indicates that a new session is started
            long continueId = -1;

            serviceIntent.putExtra(SaveMyBikeService.PARAM_SIMULATE, simulate);
            serviceIntent.putExtra(SaveMyBikeService.PARAM_CONTINUE_ID, continueId);
            serviceIntent.putExtra(SaveMyBikeService.PARAM_VEHICLE, currentVehicle);
            serviceIntent.putExtra(SaveMyBikeService.PARAM_CONFIG, getConfiguration());

            startService(serviceIntent);

            bindToService(serviceIntent);
        }
    }

    /**
     * stops recording a session
     */
    public void stopRecording() {

        if (mService != null) {
            mService.stopSession();
        }

        unbindService(mServiceConnection);
        //onServiceDisconnected is only called when service crashes, hence nullify service here
        mService = null;

        invalidateOptionsMenu();
    }

    /**
     * bind or rebind to a running service
     *
     * @param serviceIntent the intent for the service to bind to
     */
    private void bindToService(Intent serviceIntent) {

        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * the current connection to the recording service
     */
    protected ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {

            if (binder instanceof SaveMyBikeService.SaveMyBikeBinder) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "onServiceConnected");
                }
                mService = ((SaveMyBikeService.SaveMyBikeBinder) binder).getService();
                if (applyServiceVehicle) {

                    Fragment currentFragment = getCurrentFragment();
                    if (currentFragment != null && currentFragment instanceof RecordFragment && mService.getSessionLogic() != null && mService.getSessionLogic().getVehicle() != null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "rebound to service, applying vehicle " + mService.getSessionLogic().getVehicle().toString());
                        }
                        ((RecordFragment) currentFragment).invalidateUI(mService.getSessionLogic().getVehicle());
                    }

                    applyServiceVehicle = false;
                }
                invalidateOptionsMenu();
            } else {
                Log.w(TAG, "unexpected : binder is no saveMyBikeBinder");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "onServiceDisconnected");
            }
            mService = null;
        }
    };

    /**
     * navigation listener to switch between fragments
     */
    private BottomNavigationView.OnNavigationItemSelectedListener mOnNavigationItemSelectedListener
            = new BottomNavigationView.OnNavigationItemSelectedListener() {

        @Override
        public boolean onNavigationItemSelected(@NonNull MenuItem item) {
            switch (item.getItemId()) {
                case R.id.navigation_record:
                    changeFragment(0);
                    return true;
                case R.id.navigation_stats:
                    changeFragment(1);
                    return true;
                case R.id.navigation_bikes:
                    changeFragment(2);
                    return true;
            }
            return false;
        }

    };

    /**
     * load fragment (if necessary) for index @param position
     *
     * @param position menu index
     */
    public void changeFragment(int position) {

        Fragment currentFragment = getCurrentFragment();

        Fragment fragment = null;
        switch (position) {
            case 0:
                navigation.setVisibility(View.VISIBLE);
                getSupportActionBar().show();
                if (currentFragment != null && currentFragment instanceof RecordFragment) {
                    return;
                }
                fragment = new RecordFragment();
                break;
            case 1:
                if (currentFragment != null && currentFragment instanceof StatsFragment) {
                    return;
                }
                fragment = new StatsFragment();
                break;
            case 2:
                if (currentFragment != null && currentFragment instanceof BikeListFragment) {
                    return;
                }
                fragment = new BikeListFragment();
                break;
            case 3:
                if (currentFragment != null && currentFragment instanceof LoginFragment) {
                    return;
                }
                fragment = new LoginFragment();
                break;
        }

        getFragmentManager().beginTransaction().replace(R.id.content, fragment).commit();
    }

    /**
     * changes the current vehicle in the configuration and updates the UI if the record fragment is currently visible
     *
     * @param vehicleType  the new vehicle type
     * @param setInService if to inform the service about the vehicle change
     */
    public void changeVehicle(Vehicle.VehicleType vehicleType, boolean setInService) {

        for (Vehicle vehicle : getConfiguration().getVehicles()) {
            if (vehicle.getType() == vehicleType) {
                vehicle.setSelected(true);
                Fragment currentFragment = getCurrentFragment();

                if (currentFragment != null && currentFragment instanceof RecordFragment) {
                    ((RecordFragment) currentFragment).selectVehicle(vehicle);
                }
                currentVehicle = vehicle;

                if (setInService && mService != null) {
                    mService.vehicleChanged(vehicle);
                }

            } else {
                vehicle.setSelected(false);
            }
        }
    }

    /**
     * ////////////// ANDROID 6 permissions /////////////////
     * checks if the permission @param is granted and if not requests it
     *
     * @param permission the permission to check
     * @return if the permission is necessary
     */
    public boolean permissionNecessary(final String permission, final PermissionIntent intent) {

        boolean required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(getBaseContext(), permission) != PackageManager.PERMISSION_GRANTED;

        if (required) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST);
            mPermissionIntent = intent;
            return true;
        }

        return false;
    }

    /**
     * ////////////// ANDROID 6 permissions /////////////////
     * returns the result of the permission request
     *
     * @param requestCode  a requestCode
     * @param permissions  the requested permission
     * @param grantResults the result of the user decision
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        if (PERMISSION_REQUEST == requestCode) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {

                //the permission was denied by the user, show a message
                if (permissions.length > 0) {
                    if (permissions[0].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        //sdcard
                        //TODO show a message or quit app when external storage (data upload) was denied ?
                    } else if (permissions[0].equals(Manifest.permission.ACCESS_COARSE_LOCATION) || permissions[0].equals(Manifest.permission.ACCESS_FINE_LOCATION)) {

                        //location
                        Toast.makeText(getBaseContext(), R.string.permission_location_required, Toast.LENGTH_SHORT).show();
                    }
                }
                return;
            }

            //user did grant permission, what did we want to do ?
            switch (mPermissionIntent) {
                case LOCATION:
                    startRecording();
                    break;
                case SD_CARD:
                    new S3Manager(getBaseContext(), uploadWithWifiOnly).checkUpload();
                    break;

            }
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        if (getCurrentSession() == null) {

            getMenuInflater().inflate(R.menu.menu_record, menu);

            MenuItem followItem = menu.findItem(R.id.menu_simulate);
            followItem.setChecked(simulate);
            followItem.setIcon(simulate ? android.R.drawable.checkbox_on_background : android.R.drawable.checkbox_off_background);

            MenuItem wifiOnlyItem = menu.findItem(R.id.menu_upload_wifi);
            wifiOnlyItem.setChecked(uploadWithWifiOnly);
            wifiOnlyItem.setIcon(uploadWithWifiOnly ? android.R.drawable.checkbox_on_background : android.R.drawable.checkbox_off_background);

            return true;
        } else {
            return super.onCreateOptionsMenu(menu);
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.menu_simulate:

                simulate = !simulate;
                Fragment currentFragment = getCurrentFragment();
                if (currentFragment != null && currentFragment instanceof RecordFragment) {
                    ((RecordFragment) currentFragment).applySimulate(simulate);
                }
                invalidateOptionsMenu();
                break;
            case R.id.menu_upload_wifi:

                uploadWithWifiOnly = !uploadWithWifiOnly;
                //save this setting
                PreferenceManager.getDefaultSharedPreferences(getBaseContext()).edit().putBoolean(Constants.PREF_WIFI_ONLY_UPLOAD, uploadWithWifiOnly).apply();
                invalidateOptionsMenu();
                break;

        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * task to invalidate the UI, executes itself periodically every UI_UPDATE_INTERVAL
     */
    private Runnable mUpdateUITask = new Runnable() {

        public void run() {

            Fragment currentFragment = getCurrentFragment();
            if (currentFragment != null && currentFragment instanceof RecordFragment) {
                Session session = getCurrentSession();
                ((RecordFragment) currentFragment).invalidateSessionStats(session);
            }

            getHandler().postDelayed(this, UI_UPDATE_INTERVAL);
        }
    };

    /**
     * BroadcastReceiver to receive events from Notification
     */
    public class MReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (intent.getAction() == null) {
                Log.w(TAG, "unexpected intent action null");
                return;
            }

            if (intent.getAction().equals(Constants.INTENT_STOP_FROM_SERVICE)) {

                unbindService(mServiceConnection);
                //onServiceDisconnected is only called when service crashes, hence nullify service here
                mService = null;

                invalidateOptionsMenu();

                Fragment currentFragment = getCurrentFragment();
                if (currentFragment != null && currentFragment instanceof RecordFragment) {
                    ((RecordFragment) currentFragment).applySessionState(Session.SessionState.STOPPED);
                }
            } else if (intent.getAction().equals(Constants.INTENT_VEHICLE_UPDATE)) {

                if (mService != null) {
                    Vehicle newVehicle = mService.getCurrentVehicle();
                    if (newVehicle != null) {
                        changeVehicle(newVehicle.getType(), false);
                    }
                }
            }
        }
    }

    /**
     * checks if a service is running in the system
     *
     * @param context     a context
     * @param serviceName the package name of the service
     * @return true if running
     */
    public boolean isServiceRunning(@NonNull Context context, @NonNull final String serviceName) {

        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceName.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the current session when available - null otherwise
     */
    public Session getCurrentSession() {

        if (mService != null && mService.getSessionLogic() != null && mService.getSessionLogic().getSession() != null) {

            return mService.getSessionLogic().getSession();
        }
        return null;
    }

    /**
     * gets the configuration - if it is null it is loaded
     *
     * @return the configuration
     */
    public Configuration getConfiguration() {
        if (configuration == null) {
            configuration = Configuration.loadConfiguration(getBaseContext());
        }
        return configuration;
    }

    /**
     * loads the config from remote
     */
    private static class GetRemoteConfigTask extends AsyncTask<Void, Void, Void> {

        private WeakReference<Context> contextRef;
        private RetrofitClient.GetConfigCallback callback;

        GetRemoteConfigTask(final Context context, @NonNull RetrofitClient.GetConfigCallback callback) {
            this.contextRef = new WeakReference<>(context);
            this.callback = callback;
        }

        @Override
        protected Void doInBackground(Void... voids) {

            RetrofitClient retrofitClient = new RetrofitClient(contextRef.get());
            retrofitClient.getRemoteConfig(callback);
            return null;
        }
    }

    /**
     * gets the currently selected vehicle from the configuration
     *
     * @return the vehicle
     */
    public Vehicle getCurrentVehicleFromConfig() {

        for (Vehicle vehicle : getConfiguration().getVehicles()) {
            if (vehicle.isSelected()) {
                return vehicle;
            }
        }

        return null;
    }

    /**
     * @return the currently visible fragment
     */
    private Fragment getCurrentFragment() {

        return getFragmentManager().findFragmentById(R.id.content);
    }

    /**
     * @return the currently used vehicle
     */
    public Vehicle getCurrentVehicle() {
        return currentVehicle;
    }

    private Handler getHandler() {
        if (handler == null) {
            handler = new Handler();
        }
        return handler;
    }

    public MReceiver getReceiver() {

        if (mReceiver == null) {
            mReceiver = new MReceiver();
        }

        return mReceiver;
    }
}
