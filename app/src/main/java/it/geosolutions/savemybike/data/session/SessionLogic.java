package it.geosolutions.savemybike.data.session;

import android.content.Context;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Locale;
import java.util.TimerTask;

import it.geosolutions.savemybike.BuildConfig;
import it.geosolutions.savemybike.data.Constants;
import it.geosolutions.savemybike.data.dataProviders.IDataProvider;
import it.geosolutions.savemybike.data.db.SMBDatabase;
import it.geosolutions.savemybike.model.Configuration;
import it.geosolutions.savemybike.model.DataPoint;
import it.geosolutions.savemybike.model.Session;
import it.geosolutions.savemybike.model.Vehicle;

/**
 * Created by Robert Oehler on 30.10.17.
 *
 * Class which collects the data of a recording and
 * periodically {@link Constants#DEFAULT_DATA_READ_INTERVAL} adds
 * dataPoints of the current data situation to a list
 *
 * This list is periodically {@link Constants#DEFAULT_PERSISTANCE_INTERVAL}
 * persisted to the database
 */

public class SessionLogic implements IDataProvider {

    private final static String TAG = "SessionLogic";

    private Context context;
    private Vehicle vehicle;
    private Session session;
    private Handler handler;

    //configurable
    private int persistanceInterval;
    private int dataReadInterval;

    //temporay
    private boolean stopped = false;
    private boolean hasGPSFix = false;
    private boolean isSimulating;
    private long lastSessionPersistTime;
    private String databaseName;

    public SessionLogic(Context context, Session session, Vehicle vehicle, Configuration configuration) {

        this.context = context;
        this.session = session;
        this.vehicle = vehicle;

        if(configuration != null && configuration.dataReadInterval != 0 && configuration.persistanceInterval != 0){
            this.dataReadInterval = configuration.dataReadInterval;
            this.persistanceInterval = configuration.persistanceInterval;
        }else{
            this.dataReadInterval = Constants.DEFAULT_DATA_READ_INTERVAL;
            this.persistanceInterval = Constants.DEFAULT_PERSISTANCE_INTERVAL;
        }
    }

    public void start(){

        this.stopped = false;
        startTasks();
    }
    public void stop(){

        this.stopped = true;
        this.hasGPSFix = false;

        stopTasks();
    }

    /**
     * starts tasks
     */
    private void startTasks() {

        getHandler().removeCallbacks(getDataReadTask());
        getHandler().postDelayed(getDataReadTask(), dataReadInterval);

        getHandler().removeCallbacks(getPersistanceTask());
        getHandler().postDelayed(getPersistanceTask(), persistanceInterval);
    }

    /**
     * stops the tasks
     */
    private void stopTasks() {

        getHandler().removeCallbacks(getDataReadTask());
        getHandler().removeCallbacks(getPersistanceTask());
    }

    /**
     * evaluates a new location received from GPS:
     *
     * when not having a GPX fix yet register that the fix was acquired
     * the location is then used to update the current session data
     *
     * Currently there is NO filtering applied, all locations are registered
     *
     * @param newLocation the newly acquired location
     */
    public void evaluateNewLocation(Location newLocation){

        if(stopped){
            return;
        }

        if(session == null){
            Log.w(TAG, "session null");
            return;
        }

        if(!hasGPSFix){
            session.setState(Session.SessionState.ACTIVE);
            hasGPSFix = true;
        }

        //update session with the current data from the location

        session.getCurrentDataPoint().vehicleMode = vehicle.getType().ordinal();
        session.getCurrentDataPoint().timeStamp   = newLocation.getTime();
        session.getCurrentDataPoint().latitude    = newLocation.getLatitude();
        session.getCurrentDataPoint().longitude   = newLocation.getLongitude();
        session.getCurrentDataPoint().elevation   = newLocation.getAltitude();
        session.getCurrentDataPoint().accuracy    = newLocation.getAccuracy();
        session.getCurrentDataPoint().bearing     = newLocation.getBearing();
        session.getCurrentDataPoint().speed       = newLocation.getSpeed();

        //TODO add more props ?
        session.getCurrentDataPoint().orientation = context.getResources().getConfiguration().orientation;
    }

    private Runnable persistanceTask;
    private Runnable dataReadTask;

    /**
     * a task which adds the current dataPoint to the list
     * of dataPoints to persist
     * the current point is then deep copied to prepare
     * the next dataPoint with the current data as base
     */
    private Runnable getDataReadTask (){
        if(dataReadTask == null){
            dataReadTask = new Runnable() {
                @Override
                public void run() {

                    if(stopped){
                        return;
                    }

                    //add a new data point to the list of data-points

                    DataPoint newDataPoint = session.getCurrentDataPoint();
                    newDataPoint.mode = vehicle.getType().ordinal();

                    session.getDataPoints().add(newDataPoint);

                    if(BuildConfig.DEBUG){
                        Log.i(TAG, String.format(Locale.US,"did add data point %d vehicle %d lat %.4f lon %.4f", session.getDataPoints().size(), newDataPoint.vehicleMode, newDataPoint.latitude, newDataPoint.longitude));
                    }

                    session.deepCopyCurrentDataPoint();

                    getHandler().postDelayed(this, dataReadInterval);
                }
            };
        }
        return dataReadTask;
    }

    /**
     * a task which persists the current session
     */
    private Runnable getPersistanceTask() {

        if(persistanceTask == null){
            persistanceTask = new TimerTask() {
                @Override
                public void run() {

                    //15 sec interval for persistance event
                    if (System.currentTimeMillis() - lastSessionPersistTime >= persistanceInterval && session.getDataPoints().size() > 0) {

                        persistSession();

                    }else{

                        getHandler().postDelayed(this, persistanceInterval);
                    }
                }
            };
        }
        return persistanceTask;
    }

    public void persistSession() {

        //TODO persist and remember which were persisted

        if(session == null){

            Log.w(TAG, "no session available, cannot persist to db");
            getHandler().postDelayed(persistanceTask, persistanceInterval);
            return;
        }

        new AsyncTask<Session,Void,Void>(){
            @Override
            protected Void doInBackground(Session... sessions) {

                final Session session = sessions[0];

                SMBDatabase database = databaseName == null ? new SMBDatabase(context) : new SMBDatabase(context, databaseName);

                if(database.open()){

                    if (session.getId() <= 0) {
                        //this was never inserted
                        long id = database.insertSession(session, false);
                        session.setId(id);
                    } else {
                        database.insertSession(session, true);
                    }

                    //persist dataPoints
                    if (session.getDataPoints() != null) {

                        if(session.getDataPoints().size() > session.getLastPersistedIndex()) {
                            for (int i = (int) session.getLastPersistedIndex(); i < session.getDataPoints().size(); i++) {

                                final DataPoint dataPoint = session.getDataPoints().get(i);
                                if (dataPoint.sessionId <= 0) {
                                    dataPoint.sessionId = session.getId();
                                }
                                database.insertDataPoint(dataPoint);
                            }
                            session.setLastPersistedIndex(session.getDataPoints().size());
                            database.insertSession(session, true);
                            if(BuildConfig.DEBUG) {
                                Log.d(TAG, "DB : persisted to " + session.getLastPersistedIndex());
                            }
                        }
                    } else {
                        Log.i(TAG, "dataPoints null");
                    }

                    lastSessionPersistTime = System.currentTimeMillis();
                    database.close();
                }else{
                    Log.w(TAG, "could not open db");
                }
                getHandler().postDelayed(persistanceTask, persistanceInterval);
                return null;
            }
        }.execute(session);


    }

    private Handler getHandler() {
        if(handler == null){
            handler = new Handler();
        }
        return handler;
    }

    public Session getSession() {
        return session;
    }

    public void setSimulating(boolean simulating) {
        isSimulating = simulating;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public void setVehicle(Vehicle vehicle) {
        this.vehicle = vehicle;
        this.session.setCurrentVehicleType(vehicle.getType());
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public void setTestHandler(){

        HandlerThread handlerThread = new HandlerThread("HandlerThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public String getName() {
        return "SessionLogic";
    }
}
