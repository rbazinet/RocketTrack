package org.rockettrack;

import java.util.List;

import org.rockettrack.util.ExponentialAverage;
import org.rockettrack.util.Unit;
import org.rockettrack.util.UnitConverter;
import org.rockettrack.views.CoordinateHelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public abstract class RocketTrackBaseFragment
extends Fragment
implements SensorEventListener, LocationListener, GpsStatus.Listener {

	private final static String TAG = "RocketTrackBaseFragment";

	// Preferences names and default values
	private static final String PREFS_KEY_UNIT_DISTANCE = "unitPref";
	private static final String PREFS_KEY_KEEP_SCREEN_ON = "keepScreenOn";
	private static final String PREFS_KEY_AGL = "aglPref";

	// Some default values
	private static final String PREFS_DEFAULT_UNIT = Unit.meter.toString();
	private static final boolean PREFS_DEFAULT_KEEP_SCREEN_ON = false;
	private static final boolean PREFS_DEFAULT_AGL = true;

	protected Unit unitDistance = Unit.meter;
	protected boolean useAgl = true;

	private TextView distanceView;
	private TextView altView;
	private TextView maxAltView;
	private TextView bearingView;
	private TextView latView;
	private TextView lonView;

	private TextView gpsStatus;

	TransitionDrawable drawable;
	final Handler handler = new Handler();

	protected abstract void onRocketLocationChange();
	protected abstract void onCompassChange();
	protected abstract void onMyLocationChange();

	// My current location;
	private Location myLocation;

	// Current location of Rocket
	private Location rocketLocation;

	// Time we last sent a compass update to the derived class.
	// This is used to limit the number of updates
	private long lastCompassTs = 0;

	// magnatometer vector
	private float[] magnet = new float[3];

	// accelerometer vector
	private float[] accel = new float[3];

	// orientation angles from accel and magnet
	private float[] accMagOrientation = new float[3];

	// accelerometer and magnetometer based rotation matrix
	private float[] rotationMatrix = new float[9];

	// Filters for sensor values
	private ExponentialAverage accelAverage = new ExponentialAverage(3,.4f);
	private ExponentialAverage magnetAverage = new ExponentialAverage(3,.25f);

	// Sensor values
	private int mPreviousState = -1;	

	protected LocationManager mLocationManager;

	private GeomagneticField geoField;

	// Listener for rocket location changes
	private DataSetObserver mObserver = null;

	protected Location getMyLocation() {
		return myLocation;
	}

	protected Location getRocketLocation() {
		return rocketLocation;
	}

	protected List<Location> getRocketLocationHistory() {
		return RocketTrackState.getInstance().getLocationDataAdapter().getLocationHistory();
	}

	protected String getDistanceTo() {
		if ( myLocation == null || rocketLocation == null ) {
			return "";
		}
		float distanceMeters = myLocation.distanceTo(rocketLocation);
		String distanceString = UnitConverter.convertWithUnit(Unit.meter, unitDistance, distanceMeters, "#");
		return distanceString;
	}

	protected float getAzimuth() {
		if ( accMagOrientation == null ) {
			return 0f;
		}
		//Log.d(TAG, "getAzimuth " + accMagOrientation[0]);
		float azimuth = (float)Math.toDegrees(accMagOrientation[0]);
		azimuth = (azimuth +360) % 360;
		//Log.d(TAG, "getAzimuth " + azimuth);
		if (geoField != null) {
			azimuth += geoField.getDeclination();
		}
		//Log.d(TAG, "getAzimuth " + azimuth);
		return azimuth;
	}

	protected Integer getBearing() {
		if ( rocketLocation == null || myLocation == null ) {
			return null;
		}
		float rocketBearing = normalizeDegree(myLocation.bearingTo(getRocketLocation()));
		return Math.round(rocketBearing);
	}

	private float normalizeDegree(float value){
		if(value >= 0.0f && value <= 180.0f){
			return value;
		}else{
			return 180 + (180 + value);
		}	
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		distanceView = (TextView) getView().findViewById(R.id.Distance);
		altView = (TextView) getView().findViewById(R.id.Altitude);
		maxAltView = (TextView) getView().findViewById(R.id.MaxAlt);
		bearingView = (TextView) getView().findViewById(R.id.Bearing);
		latView = (TextView) getView().findViewById(R.id.Latitude);
		lonView = (TextView) getView().findViewById(R.id.Longitude);
		gpsStatus = (TextView) getView().findViewById(R.id.GpsStatus);

		Drawable[] colors = new Drawable[] {
				new ColorDrawable(Color.GREEN),
				new ColorDrawable(Color.RED)
		};
		drawable = new TransitionDrawable(colors);
		drawable.setCrossFadeEnabled(true);
		gpsStatus.setBackgroundDrawable(drawable);
		drawable.startTransition(0);
		//gpsStatus.setBackgroundColor(Color.RED);
	}

	@Override
	public void onResume() {
		super.onResume();

		mLocationManager = (LocationManager) getActivity().getSystemService(Context.LOCATION_SERVICE);

		// Read GPS minimum time preferences
		final int intMinTime = 1000;
		// Read GPS minimum distance preference
		final float fltMinDistance = 3;

		// Location (GPS)
		this.mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intMinTime, fltMinDistance, this);

		myLocation = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

		// Add GpsStatusListener
		this.mLocationManager.addGpsStatusListener(this);

		mObserver = new DataSetObserver() {
			@Override
			public void onChanged() {
				//Log.d(TAG,"DataSetObserver.onChanged()");
				updateRocketLocation();
			}

			@Override
			public void onInvalidated() {
				//Log.d(TAG,"DataSetObserver.onInvalidated()");
				updateRocketLocation();
			}
		};

		RocketTrackState.getInstance().getLocationDataAdapter().registerDataSetObserver(mObserver);
		rocketLocation = RocketTrackState.getInstance().getLocationDataAdapter().getRocketPosition();

		SensorManager sman = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);

		sman.registerListener(this,
				sman.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				SensorManager.SENSOR_DELAY_UI);
		sman.registerListener(this,
				sman.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
				SensorManager.SENSOR_DELAY_UI);

		getPreferences();
	}

	@Override
	public void onPause() {
		super.onPause();
		RocketTrackState.getInstance().getLocationDataAdapter().unregisterDataSetObserver(mObserver);

		SensorManager sman = (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);

		sman.unregisterListener(this);

		mLocationManager.removeGpsStatusListener(this);
		mLocationManager.removeUpdates(this);

	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		switch(event.sensor.getType()) {
		case Sensor.TYPE_ACCELEROMETER:
			accel = accelAverage.average(event.values);
			break;

		case Sensor.TYPE_MAGNETIC_FIELD:

			magnet = magnetAverage.average(event.values);
			break;

		}

		if ( event.timestamp > lastCompassTs + 250000000 /* .25s in nanoseconds */) {
			lastCompassTs = event.timestamp;

			float[] unmappedRotationMatrix = new float[9];

			SensorManager.getRotationMatrix(unmappedRotationMatrix, null, accel, magnet);

			WindowManager wm = (WindowManager) getActivity().getSystemService(Context.WINDOW_SERVICE);
			int currentOrientation = wm.getDefaultDisplay().getRotation();
			//Log.d(TAG,"current orientation " + currentOrientation);

			switch ( currentOrientation ) {
			case Surface.ROTATION_0:
				SensorManager.remapCoordinateSystem(unmappedRotationMatrix,SensorManager.AXIS_X,SensorManager.AXIS_Y,rotationMatrix);
				break;
			case Surface.ROTATION_90:
				SensorManager.remapCoordinateSystem(unmappedRotationMatrix,SensorManager.AXIS_Y,SensorManager.AXIS_X,rotationMatrix);
				break;
			case Surface.ROTATION_180:
				SensorManager.remapCoordinateSystem(unmappedRotationMatrix,SensorManager.AXIS_X,SensorManager.AXIS_MINUS_Y,rotationMatrix);
				break;
			case Surface.ROTATION_270:
				SensorManager.remapCoordinateSystem(unmappedRotationMatrix,SensorManager.AXIS_MINUS_Y,SensorManager.AXIS_MINUS_X,rotationMatrix);
				break;
			}
			SensorManager.getOrientation(rotationMatrix, accMagOrientation);

			updateBearingAndDistance();
			onCompassChange();
		}

	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}

	@Override
	public void onGpsStatusChanged(int event) 
	{
		// Default implemenation
	}

	@Override
	final public void onLocationChanged(Location location) 
	{
		myLocation = location;
		geoField = new GeomagneticField(
				Double.valueOf(location.getLatitude()).floatValue(),
				Double.valueOf(location.getLongitude()).floatValue(),
				Double.valueOf(location.getAltitude()).floatValue(),
				System.currentTimeMillis()
				);
		updateBearingAndDistance();
		this.onMyLocationChange();
	}

	@Override
	public void onProviderDisabled(String provider) 
	{
	}

	@Override
	public void onProviderEnabled(String provider) 
	{
	}

	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) 
	{
		if (status != this.mPreviousState)
		{
			String strNewStatus = String.format("GPS Status: ", provider);
			if (status == LocationProvider.AVAILABLE)
				strNewStatus += "Available";
			else if (status == LocationProvider.OUT_OF_SERVICE)
				strNewStatus += "Out of service";
			else if (status == LocationProvider.TEMPORARILY_UNAVAILABLE)
				strNewStatus += "Temporarily unavailable";

			Toast.makeText(getActivity(), strNewStatus, Toast.LENGTH_SHORT).show();
			this.mPreviousState = status;
		}
	}

	/**
	 * 
	 */
	protected void getPreferences()
	{
		// Load preferences
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());

		final String strUnit = sharedPreferences.getString(PREFS_KEY_UNIT_DISTANCE, PREFS_DEFAULT_UNIT);
		unitDistance = Unit.getUnitForString(strUnit);

		// Set keep screen on property
		final boolean blnKeepScreenOn = sharedPreferences.getBoolean(PREFS_KEY_KEEP_SCREEN_ON, PREFS_DEFAULT_KEEP_SCREEN_ON);
		this.getView().setKeepScreenOn(blnKeepScreenOn);

		// Load the agl pref
		useAgl = sharedPreferences.getBoolean(PREFS_KEY_AGL, PREFS_DEFAULT_AGL);

	}

	private void updateBearingAndDistance() {
		if ( myLocation == null || rocketLocation == null ) {
			bearingView.setText("");
			distanceView.setText("");
			return;
		}

		Integer rocketBearing = getBearing();
		if ( rocketBearing != null ) {
			bearingView.setText(String.valueOf(rocketBearing));
		}

		//Rocket Distance
		String rocketDistance = this.getDistanceTo();
		//TextView lblDistance = (TextView) getView().findViewById(R.id.Distance);
		distanceView.setText(rocketDistance );

	}

	private void updateRocketLocation() {
		drawable.resetTransition();
		drawable.startTransition(1500);
		rocketLocation = RocketTrackState.getInstance().getLocationDataAdapter().getRocketPosition();
		updateBearingAndDistance();

		if ( rocketLocation == null ) {
			latView.setText("");
			lonView.setText("");
			altView.setText("");
			maxAltView.setText("");
			return;
		}

		// Lat & Lon
		{
			final CoordinateHelper coordinateHelper = new CoordinateHelper(rocketLocation.getLatitude(), rocketLocation.getLongitude());
			latView.setText(coordinateHelper.getLatitudeString());
			lonView.setText(coordinateHelper.getLongitudeString());
		}

		//Max Altitude
		{
			double altitude = rocketLocation.getAltitude();
			if( useAgl ) {
				altitude -= myLocation.getAltitude();
			}
			String altString = UnitConverter.convertWithUnit(Unit.meter, unitDistance, altitude, "#");
			altView.setText(altString);

			double maxAltitude = RocketTrackState.getInstance().getLocationDataAdapter().getMaxAltitude();
			if( useAgl ) {
				maxAltitude -= myLocation.getAltitude();
			}
			String maxAltString = UnitConverter.convertWithUnit(Unit.meter, unitDistance, maxAltitude, "#");
			maxAltView.setText(maxAltString);
		}

		RocketTrackBaseFragment.this.onRocketLocationChange();


	}

}
