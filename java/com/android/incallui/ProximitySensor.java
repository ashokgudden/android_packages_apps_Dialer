/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.incallui;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.telecom.CallAudioState;
import android.view.Display;
import com.android.dialer.common.LogUtil;
import com.android.incallui.InCallPresenter.InCallState;
import com.android.incallui.InCallPresenter.InCallStateListener;
import com.android.incallui.call.TelecomAdapter;
import com.android.incallui.audiomode.AudioModeProvider;
import com.android.incallui.audiomode.AudioModeProvider.AudioModeListener;
import com.android.incallui.call.CallList;
import com.android.incallui.call.DialerCall;

/**
 * Class manages the proximity sensor for the in-call UI. We enable the proximity sensor while the
 * user in a phone call. The Proximity sensor turns off the touchscreen and display when the user is
 * close to the screen to prevent user's cheek from causing touch events. The class requires special
 * knowledge of the activity and device state to know when the proximity sensor should be enabled
 * and disabled. Most of that state is fed into this class through public methods.
 */
public class ProximitySensor
    implements AccelerometerListener.ChangeListener, InCallStateListener, AudioModeListener, SensorEventListener {

  private static final String TAG = ProximitySensor.class.getSimpleName();

  private final PowerManager mPowerManager;
  private final PowerManager.WakeLock mProximityWakeLock;
  private SensorManager mSensor;
  private Sensor mProxSensor;
  private final AudioModeProvider mAudioModeProvider;
  private final AccelerometerListener mAccelerometerListener;
  private final ProximityDisplayListener mDisplayListener;
  private int mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;
  private boolean mUiShowing = false;
  private boolean mHasIncomingCall = false;
  private boolean mIsPhoneOffhook = false;
  private boolean mIsPhoneOutgoing = false;
  private boolean mIsProxSensorFar = true;
  private boolean mDialpadVisible;
  private boolean mIsAttemptingVideoCall;
  private boolean mIsVideoCall;
  private Context mContext;

  private SharedPreferences mPrefs;

  private final Handler mHandler = new Handler();
  private final Runnable mActivateSpeaker = new Runnable() {
    @Override
    public void run() {
      TelecomAdapter.getInstance().setAudioRoute(CallAudioState.ROUTE_SPEAKER);
    }
  };

  public ProximitySensor(
      @NonNull Context context,
      @NonNull AudioModeProvider audioModeProvider,
      @NonNull AccelerometerListener accelerometerListener) {
    mContext = context;
    mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    if (mPowerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
      mProximityWakeLock =
          mPowerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG);
    } else {
      LogUtil.i("ProximitySensor.constructor", "Device does not support proximity wake lock.");
      mProximityWakeLock = null;
    }
    if (mPowerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
      mSensor = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
      mProxSensor = mSensor.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    } else {
      mProxSensor = null;
      mSensor = null;
    }
    mAccelerometerListener = accelerometerListener;
    mAccelerometerListener.setListener(this);

    mDisplayListener =
        new ProximityDisplayListener(
            (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE));
    mDisplayListener.register();

    mAudioModeProvider = audioModeProvider;
    mAudioModeProvider.addListener(this);

    mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
  }

  public void tearDown() {
    mAudioModeProvider.removeListener(this);

    mAccelerometerListener.enable(false);
    mDisplayListener.unregister();

    turnOffProximitySensor(true);

    if (mSensor != null) {
      mSensor.unregisterListener(this);
    }

    // remove any pending audio changes scheduled
    mHandler.removeCallbacks(mActivateSpeaker);
  }

  /** Called to identify when the device is laid down flat. */
  @Override
  public void onOrientationChanged(int orientation) {
    mOrientation = orientation;
    updateProximitySensorMode();
  }

  @Override
  public void onDeviceFlipped(boolean faceDown) {
      // ignored
  }

  /** Called to keep track of the overall UI state. */
  @Override
  public void onStateChange(InCallState oldState, InCallState newState, CallList callList) {
    // We ignore incoming state because we do not want to enable proximity
    // sensor during incoming call screen. We check hasLiveCall() because a disconnected call
    // can also put the in-call screen in the INCALL state.
    boolean hasOngoingCall = InCallState.INCALL == newState && callList.hasLiveCall();
    boolean isOffhook = (InCallState.OUTGOING == newState) || hasOngoingCall;
    mHasIncomingCall = (InCallState.INCOMING == newState);
    mIsPhoneOutgoing = (InCallState.OUTGOING == newState);

    DialerCall activeCall = callList.getActiveCall();
    boolean isVideoCall = activeCall != null && activeCall.isVideoCall();

    if (isOffhook != mIsPhoneOffhook || mIsVideoCall != isVideoCall) {
      mIsPhoneOffhook = isOffhook;
      mIsVideoCall = isVideoCall;

      mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;
      mAccelerometerListener.enable(mIsPhoneOffhook);

      updateProxSpeaker();
      updateProximitySensorMode();
    }

    if (hasOngoingCall && InCallState.OUTGOING == oldState) {
      setProxSpeaker(mIsProxSensorFar);
    }

    if (mHasIncomingCall) {
      updateProximitySensorMode();
    }
  }

  @Override
  public void onAudioStateChanged(CallAudioState audioState) {
    updateProximitySensorMode();
  }

  /**
   * Proximity state changed
   */
  @Override
  public void onSensorChanged(SensorEvent event) {
    if (event.values[0] != mProxSensor.getMaximumRange()) {
      mIsProxSensorFar = false;
    } else {
      mIsProxSensorFar = true;
    }

    setProxSpeaker(mIsProxSensorFar);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
  }

  public void onDialpadVisible(boolean visible) {
    mDialpadVisible = visible;
    updateProximitySensorMode();
  }

  public void setIsAttemptingVideoCall(boolean isAttemptingVideoCall) {
    LogUtil.i(
        "ProximitySensor.setIsAttemptingVideoCall",
        "isAttemptingVideoCall: %b",
        isAttemptingVideoCall);
    mIsAttemptingVideoCall = isAttemptingVideoCall;
    updateProximitySensorMode();
  }
  /** Used to save when the UI goes in and out of the foreground. */
  public void onInCallShowing(boolean showing) {
    if (showing) {
      mUiShowing = true;

      // We only consider the UI not showing for instances where another app took the foreground.
      // If we stopped showing because the screen is off, we still consider that showing.
    } else if (mPowerManager.isScreenOn()) {
      mUiShowing = false;
    }
    updateProximitySensorMode();
  }

  void onDisplayStateChanged(boolean isDisplayOn) {
    LogUtil.i("ProximitySensor.onDisplayStateChanged", "isDisplayOn: %b", isDisplayOn);
    mAccelerometerListener.enable(isDisplayOn);
  }

  /**
   * TODO: There is no way to determine if a screen is off due to proximity or if it is legitimately
   * off, but if ever we can do that in the future, it would be useful here. Until then, this
   * function will simply return true of the screen is off. TODO: Investigate whether this can be
   * replaced with the ProximityDisplayListener.
   */
  public boolean isScreenReallyOff() {
    return !mPowerManager.isScreenOn();
  }

  private void turnOnProximitySensor() {
    if (mProximityWakeLock != null) {
      if (!mProximityWakeLock.isHeld()) {
        LogUtil.i("ProximitySensor.turnOnProximitySensor", "acquiring wake lock");
        mProximityWakeLock.acquire();
      } else {
        LogUtil.i("ProximitySensor.turnOnProximitySensor", "wake lock already acquired");
      }
    }
  }

  private void turnOffProximitySensor(boolean screenOnImmediately) {
    if (mProximityWakeLock != null) {
      if (mProximityWakeLock.isHeld()) {
        LogUtil.i("ProximitySensor.turnOffProximitySensor", "releasing wake lock");
        int flags = (screenOnImmediately ? 0 : PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        mProximityWakeLock.release(flags);
      } else {
        LogUtil.i("ProximitySensor.turnOffProximitySensor", "wake lock already released");
      }
    }
  }

  /**
   * Updates the wake lock used to control proximity sensor behavior, based on the current state of
   * the phone.
   *
   * <p>On devices that have a proximity sensor, to avoid false touches during a call, we hold a
   * PROXIMITY_SCREEN_OFF_WAKE_LOCK wake lock whenever the phone is off hook. (When held, that wake
   * lock causes the screen to turn off automatically when the sensor detects an object close to the
   * screen.)
   *
   * <p>This method is a no-op for devices that don't have a proximity sensor.
   *
   * <p>Proximity wake lock will be released if any of the following conditions are true: the audio
   * is routed through bluetooth, a wired headset, or the speaker; the user requested, received a
   * request for, or is in a video call; or the phone is horizontal while in a call.
   */
  private synchronized void updateProximitySensorMode() {
    final int audioRoute = mAudioModeProvider.getAudioState().getRoute();

    boolean screenOnImmediately =
        (CallAudioState.ROUTE_WIRED_HEADSET == audioRoute
            || CallAudioState.ROUTE_SPEAKER == audioRoute
            || CallAudioState.ROUTE_BLUETOOTH == audioRoute
            || mIsAttemptingVideoCall
            || mIsVideoCall);

    // We do not keep the screen off when the user is outside in-call screen and we are
    // horizontal, but we do not force it on when we become horizontal until the
    // proximity sensor goes negative.
    final boolean horizontal = (mOrientation == AccelerometerListener.ORIENTATION_HORIZONTAL);
    screenOnImmediately |= !mUiShowing && horizontal;

    // We do not keep the screen off when dialpad is visible, we are horizontal, and
    // the in-call screen is being shown.
    // At that moment we're pretty sure users want to use it, instead of letting the
    // proximity sensor turn off the screen by their hands.
    screenOnImmediately |= mDialpadVisible && horizontal;

    LogUtil.i(
        "ProximitySensor.updateProximitySensorMode",
        "screenOnImmediately: %b, dialPadVisible: %b, "
            + "offHook: %b, horizontal: %b, uiShowing: %b, audioRoute: %s",
        screenOnImmediately,
        mDialpadVisible,
        mIsPhoneOffhook,
        mOrientation == AccelerometerListener.ORIENTATION_HORIZONTAL,
        mUiShowing,
        CallAudioState.audioRouteToString(audioRoute));

    if ((mIsPhoneOffhook || mHasIncomingCall) && !screenOnImmediately) {
      LogUtil.v("ProximitySensor.updateProximitySensorMode", "turning on proximity sensor");
      // Phone is in use!  Arrange for the screen to turn off
      // automatically when the sensor detects a close object.
      turnOnProximitySensor();
    } else {
      LogUtil.v("ProximitySensor.updateProximitySensorMode", "turning off proximity sensor");
      // Phone is either idle, or ringing.  We don't want any special proximity sensor
      // behavior in either case.
      turnOffProximitySensor(screenOnImmediately);
    }
  }

  private void updateProxSpeaker() {
    if (mSensor != null && mProxSensor != null) {
      if (mIsPhoneOffhook) {
        mSensor.registerListener(this, mProxSensor,
            SensorManager.SENSOR_DELAY_NORMAL);
      } else {
        mSensor.unregisterListener(this);
      }
    }
  }

  private void setProxSpeaker(final boolean speaker) {
    // remove any pending audio changes scheduled
    mHandler.removeCallbacks(mActivateSpeaker);

    final int audioState = mAudioModeProvider.getAudioState().getRoute();
    final boolean isProxSpeakerEnabled =
        mPrefs.getBoolean("proximity_auto_speaker", false);;
    final boolean proxSpeakerIncallOnlyPref =
        mPrefs.getBoolean("proximity_auto_speaker_incall_only", false);
    final int proxSpeakerDelay = Integer.valueOf(
        mPrefs.getString("proximity_auto_speaker_delay", "3000"));
    // if phone off hook (call in session), and prox speaker feature is on
    if (mIsPhoneOffhook && isProxSpeakerEnabled
        // as long as AudioState isn't currently wired headset or bluetooth
        && audioState != CallAudioState.ROUTE_WIRED_HEADSET
        && audioState != CallAudioState.ROUTE_BLUETOOTH) {
       // okay, we're good to start switching audio mode on proximity
       // if proximity sensor determines audio mode should be speaker,
      // but it currently isn't
      if (speaker && audioState != CallAudioState.ROUTE_SPEAKER) {

        // if prox incall only is off, we set to speaker as long as phone
        // is off hook, ignoring whether or not the call state is outgoing
        if (!proxSpeakerIncallOnlyPref
            // or if prox incall only is on, we have to check the call
            // state to decide if AudioState should be speaker
            || (proxSpeakerIncallOnlyPref && !mIsPhoneOutgoing)) {
          mHandler.postDelayed(mActivateSpeaker, proxSpeakerDelay);
        }
      } else if (!speaker) {
        TelecomAdapter.getInstance().setAudioRoute(CallAudioState.ROUTE_EARPIECE);
      }
    }
  }

  /**
   * Implementation of a {@link DisplayListener} that maintains a binary state: Screen on vs screen
   * off. Used by the proximity sensor manager to decide whether or not it needs to listen to
   * accelerometer events.
   */
  public class ProximityDisplayListener implements DisplayListener {

    private DisplayManager mDisplayManager;
    private boolean mIsDisplayOn = true;

    ProximityDisplayListener(DisplayManager displayManager) {
      mDisplayManager = displayManager;
    }

    void register() {
      mDisplayManager.registerDisplayListener(this, null);
    }

    void unregister() {
      mDisplayManager.unregisterDisplayListener(this);
    }

    @Override
    public void onDisplayRemoved(int displayId) {}

    @Override
    public void onDisplayChanged(int displayId) {
      if (displayId == Display.DEFAULT_DISPLAY) {
        final Display display = mDisplayManager.getDisplay(displayId);

        final boolean isDisplayOn = display.getState() != Display.STATE_OFF;
        // For call purposes, we assume that as long as the screen is not truly off, it is
        // considered on, even if it is in an unknown or low power idle state.
        if (isDisplayOn != mIsDisplayOn) {
          mIsDisplayOn = isDisplayOn;
          onDisplayStateChanged(mIsDisplayOn);
        }
      }
    }

    @Override
    public void onDisplayAdded(int displayId) {}
  }
}
