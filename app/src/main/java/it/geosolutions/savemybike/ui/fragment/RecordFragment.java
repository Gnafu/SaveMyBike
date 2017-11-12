package it.geosolutions.savemybike.ui.fragment;

import android.app.Fragment;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

import it.geosolutions.savemybike.R;
import it.geosolutions.savemybike.data.Util;
import it.geosolutions.savemybike.model.Session;
import it.geosolutions.savemybike.model.Vehicle;
import it.geosolutions.savemybike.ui.activity.SaveMyBikeActivity;

/**
 * Created by Robert Oehler on 25.10.17.
 *
 */

public class RecordFragment extends Fragment {

    private final static String TAG = "RecordFragment";

    private ArrayList<View> modeViews;

    private ImageView recordButton;
    private TextView simulateTV;
    private TextView distTV;
    private TextView timeTV;
    private LinearLayout statsRow;

    private boolean statsHidden = true;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {

        final View view = inflater.inflate(R.layout.fragment_record, container,false);

        getModeViews().add(view.findViewById(R.id.mode_foot));
        getModeViews().add(view.findViewById(R.id.mode_bike));
        getModeViews().add(view.findViewById(R.id.mode_bus));
        getModeViews().add(view.findViewById(R.id.mode_car));

        for(View iv : modeViews){

            iv.setOnClickListener(modeClickListener);
        }
        recordButton = view.findViewById(R.id.record_button);
        recordButton.setOnClickListener(modeClickListener);

        simulateTV = view.findViewById(R.id.simulate_tv);
        distTV     = view.findViewById(R.id.stats_dist);
        timeTV     = view.findViewById(R.id.stats_time);
        statsRow   = view.findViewById(R.id.stats_row);

        selectVehicle(((SaveMyBikeActivity)getActivity()).getCurrentVehicle());

        Session session = ((SaveMyBikeActivity) getActivity()).getCurrentSession();
        if(session == null){
            applySessionState(Session.SessionState.STOPPED);
        }else{
            applySessionState(session.getState());
        }

        return view;
    }

    public void selectVehicle(Vehicle vehicle){

        for(int i = 0; i < getModeViews().size(); i++){

            if(vehicle.getType().ordinal() == i){

                //select
                Drawable mWrappedDrawable = DrawableCompat.wrap(((ImageView)getModeViews().get(i)).getDrawable().mutate());
                DrawableCompat.setTint(mWrappedDrawable, ContextCompat.getColor(getActivity(), android.R.color.white));
                DrawableCompat.setTintMode(mWrappedDrawable, PorterDuff.Mode.SRC_IN);
                ((ImageView)getModeViews().get(i)).setImageDrawable(mWrappedDrawable);

                getModeViews().get(i).setBackground(ContextCompat.getDrawable(getActivity(), R.drawable.mode_selected));
            }else{

                //unselect
                Drawable mWrappedDrawable = DrawableCompat.wrap(((ImageView)getModeViews().get(i)).getDrawable().mutate());
                DrawableCompat.setTint(mWrappedDrawable, ContextCompat.getColor(getActivity(), android.R.color.black));
                DrawableCompat.setTintMode(mWrappedDrawable, PorterDuff.Mode.SRC_IN);
                ((ImageView)getModeViews().get(i)).setImageDrawable(mWrappedDrawable);

                getModeViews().get(i).setBackground(ContextCompat.getDrawable(getActivity(), R.drawable.mode_bordered));
            }
        }
    }

     private View.OnClickListener modeClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {

            switch (view.getId()){
                case R.id.mode_foot:
                    ((SaveMyBikeActivity)getActivity()).changeVehicle(Vehicle.VehicleType.FOOT);
                    break;
                case R.id.mode_bike:
                    ((SaveMyBikeActivity)getActivity()).changeVehicle(Vehicle.VehicleType.BIKE);
                    break;
                case R.id.mode_bus:
                    ((SaveMyBikeActivity)getActivity()).changeVehicle(Vehicle.VehicleType.BUS);
                    break;
                case R.id.mode_car:
                    ((SaveMyBikeActivity)getActivity()).changeVehicle(Vehicle.VehicleType.CAR);
                    break;
                case R.id.record_button:

                    //detect if we are currently recording or not
                    Session currentSession = null;

                    if(((SaveMyBikeActivity)getActivity()).getCurrentSession() != null){
                        currentSession = ((SaveMyBikeActivity)getActivity()).getCurrentSession();
                    }

                    if(currentSession != null && currentSession.getState() == Session.SessionState.ACTIVE){

                        //stop service
                        ((SaveMyBikeActivity)getActivity()).stopRecording();

                        applySessionState(Session.SessionState.STOPPED);
                    } else {

                        ((SaveMyBikeActivity)getActivity()).startRecording();

                        applySessionState(Session.SessionState.ACTIVE);
                    }
                    break;
            }
        }
    };

    public void applySessionState(final Session.SessionState state){

        switch (state){

            case ACTIVE:
                //switch to "Pause" UI
                recordButton.setImageDrawable(ContextCompat.getDrawable(getActivity(), R.drawable.ic_pause));
                break;
            case STOPPED:
                //switch to "Record" UI
                recordButton.setImageDrawable(ContextCompat.getDrawable(getActivity(), R.drawable.ic_record));
                break;
        }
    }
    public void applySimulate(boolean simulate){

        simulateTV.setVisibility(simulate ? View.VISIBLE : View.INVISIBLE);
    }

    public void invalidate(final Session session){

        if(session != null){

            if(statsHidden){
                statsRow.setVisibility(View.VISIBLE);
                statsHidden = false;
            }

            final double dist = session.getDistance();
            final long time = session.getOverallTime();

            distTV.setText(String.format(Locale.US,"%.1f km", dist / 1000f));
            timeTV.setText(Util.longToTimeString(time));

        }else{
            if(!statsHidden){
                statsRow.setVisibility(View.INVISIBLE);
                statsHidden = true;
            }
        }
    }

    public ArrayList<View> getModeViews() {

        if(modeViews == null){
            modeViews = new ArrayList<>();
        }

        return modeViews;
    }
}
