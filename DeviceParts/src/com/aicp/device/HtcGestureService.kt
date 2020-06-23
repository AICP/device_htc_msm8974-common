/*
 * Copyright (C) 2014 SlimRoms Project
 *               2016 The CyanogenMod Project
 *               2017 The LineageOS Project
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
 * limitations under the License.
 */
package com.aicp.device

import android.app.Service
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.SensorEvent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.os.SystemClock
import android.os.UserHandle
import android.os.Vibrator
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.preference.TwoStatePreference
import com.aicp.device.GestureMotionSensor.GestureMotionSensorListener
import com.android.internal.util.aicp.AicpVibe

class HtcGestureService : Service() {
    private var mContext: Context? = null
    private var mGestureSensor: GestureMotionSensor? = null
    private var mPowerManager: PowerManager? = null
    private var mSensorWakeLock: WakeLock? = null
    private var mCameraManager: CameraManager? = null
    private var mTorchCameraId: String? = null
    private var mTorchEnabled = false
    private var mAudioManager: AudioManager? = null
    private var mVibrator: Vibrator? = null
    private var mSwipeUpAction = 0
    private var mSwipeDownAction = 0
    private var mSwipeLeftAction = 0
    private var mSwipeRightAction = 0
    private val mListener = GestureMotionSensorListener { type, event ->
        if (DEBUG) Log.d(TAG, "Received event: $type")
        when (type) {
            GestureMotionSensor.Companion.SENSOR_GESTURE_DOUBLE_TAP -> mPowerManager.wakeUp(SystemClock.uptimeMillis())
            GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_UP, GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_DOWN, GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_LEFT, GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_RIGHT -> handleGestureAction(gestureToAction(type))
            GestureMotionSensor.Companion.SENSOR_GESTURE_CAMERA -> handleCameraActivation()
        }
    }

    fun onCreate() {
        if (DEBUG) Log.d(TAG, "Creating service")
        super.onCreate()
        mContext = this
        mGestureSensor = GestureMotionSensor.Companion.getInstance(mContext)
        mGestureSensor!!.registerListener(mListener)
        val sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext)
        loadPreferences(sharedPrefs)
        sharedPrefs.registerOnSharedPreferenceChangeListener(mPrefListener)
        mPowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        mSensorWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HtcGestureWakeLock")
        mCameraManager = mContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        mCameraManager.registerTorchCallback(mTorchCallback, null)
        mTorchCameraId = torchCameraId
        mAudioManager = mContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mVibrator = mContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) Log.d(TAG, "Starting service")
        val screenStateFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mContext.registerReceiver(mScreenStateReceiver, screenStateFilter)
        return START_STICKY
    }

    fun onDestroy() {
        if (DEBUG) Log.d(TAG, "Destroying service")
        super.onDestroy()
        unregisterReceiver(mScreenStateReceiver)
    }

    fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun onDisplayOn() {
        if (DEBUG) Log.d(TAG, "Display on")
        if (isDoubleTapEnabled) {
            mGestureSensor!!.disableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_DOUBLE_TAP)
        }
        if (mSwipeUpAction != ACTION_NONE) {
            mGestureSensor!!.disableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_UP)
        }
        if (mSwipeDownAction != ACTION_NONE) {
            mGestureSensor!!.disableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_DOWN)
        }
        if (mSwipeLeftAction != ACTION_NONE) {
            mGestureSensor!!.disableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_LEFT)
        }
        if (mSwipeRightAction != ACTION_NONE) {
            mGestureSensor!!.disableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_RIGHT)
        }
        mGestureSensor!!.stopListening()
    }

    private fun onDisplayOff() {
        if (DEBUG) Log.d(TAG, "Display off")
        if (isDoubleTapEnabled) {
            mGestureSensor!!.enableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_DOUBLE_TAP)
        }
        if (mSwipeUpAction != ACTION_NONE) {
            mGestureSensor!!.enableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_UP)
        }
        if (mSwipeDownAction != ACTION_NONE) {
            mGestureSensor!!.enableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_DOWN)
        }
        if (mSwipeLeftAction != ACTION_NONE) {
            mGestureSensor!!.enableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_LEFT)
        }
        if (mSwipeRightAction != ACTION_NONE) {
            mGestureSensor!!.enableGesture(GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_RIGHT)
        }
        mGestureSensor!!.beginListening()
    }

    private val isDoubleTapEnabled: Boolean
        private get() = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOUBLE_TAP_TO_WAKE, 0) !== 0

    private fun handleGestureAction(action: Int) {
        if (DEBUG) Log.d(TAG, "Performing gesture action: $action")
        when (action) {
            ACTION_CAMERA -> handleCameraActivation()
            ACTION_TORCH -> handleFlashlightActivation()
            ACTION_WAKE_DISPLAY -> handleWakeDisplay()
            ACTION_NONE -> {
            }
            else -> {
            }
        }
    }

    private fun handleCameraActivation() {
        doHapticFeedback()
        launchCamera()
    }

    private fun handleFlashlightActivation() {
        doHapticFeedback()
        launchFlashlight()
    }

    private fun handleWakeDisplay() {
        doHapticFeedback()
        wakeDisplay()
    }

    private fun launchCamera() {
        wakeDisplay()
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            mContext.startActivityAsUser(intent, null, UserHandle(UserHandle.USER_CURRENT))
        } catch (e: ActivityNotFoundException) {
            /* Ignore */
        }
    }

    private fun wakeDisplay() {
        mSensorWakeLock.acquire(SENSOR_WAKELOCK_DURATION)
        mPowerManager.wakeUp(SystemClock.uptimeMillis())
    }

    private fun launchFlashlight() {
        mSensorWakeLock.acquire(SENSOR_WAKELOCK_DURATION)
        mPowerManager.wakeUp(SystemClock.uptimeMillis())
        try {
            mCameraManager.setTorchMode(mTorchCameraId, !mTorchEnabled)
        } catch (e: CameraAccessException) {
            // Ignore
        }
    }

    private fun doHapticFeedback() {
        AicpVibe.performHapticFeedbackLw(HapticFeedbackConstants.LONG_PRESS, false, mContext, GESTURE_HAPTIC_SETTINGS_VARIABLE_NAME, GESTURE_HAPTIC_DURATION)
    }

    // Ignore
    private val torchCameraId: String?
        private get() {
            try {
                for (id in mCameraManager.getCameraIdList()) {
                    val cc: CameraCharacteristics = mCameraManager.getCameraCharacteristics(id)
                    val direction: Int = cc.get(CameraCharacteristics.LENS_FACING)
                    if (direction == CameraCharacteristics.LENS_FACING_BACK) {
                        return id
                    }
                }
            } catch (e: CameraAccessException) {
                // Ignore
            }
            return null
        }

    private val mTorchCallback: CameraManager.TorchCallback = object : TorchCallback() {
        fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != mTorchCameraId) return
            mTorchEnabled = enabled
        }

        fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId != mTorchCameraId) return
            mTorchEnabled = false
        }
    }
    private val mScreenStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        fun onReceive(context: Context?, intent: Intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                onDisplayOff()
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                onDisplayOn()
            }
        }
    }

    private fun gestureToAction(gesture: Int): Int {
        return when (gesture) {
            GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_UP -> mSwipeUpAction
            GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_DOWN -> mSwipeDownAction
            GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_LEFT -> mSwipeLeftAction
            GestureMotionSensor.Companion.SENSOR_GESTURE_SWIPE_RIGHT -> mSwipeRightAction
            else -> -1
        }
    }

    private fun loadPreferences(sharedPreferences: SharedPreferences) {
        try {
            mSwipeUpAction = sharedPreferences.getString(KEY_SWIPE_UP,
                    Integer.toString(ACTION_NONE)).toInt()
            mSwipeDownAction = sharedPreferences.getString(KEY_SWIPE_DOWN,
                    Integer.toString(ACTION_NONE)).toInt()
            mSwipeLeftAction = sharedPreferences.getString(KEY_SWIPE_LEFT,
                    Integer.toString(ACTION_NONE)).toInt()
            mSwipeRightAction = sharedPreferences.getString(KEY_SWIPE_RIGHT,
                    Integer.toString(ACTION_NONE)).toInt()
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Error loading preferences")
        }
    }

    private val mPrefListener: SharedPreferences.OnSharedPreferenceChangeListener = object : OnSharedPreferenceChangeListener() {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            try {
                if (KEY_SWIPE_UP == key) {
                    mSwipeUpAction = sharedPreferences.getString(KEY_SWIPE_UP,
                            Integer.toString(ACTION_NONE)).toInt()
                } else if (KEY_SWIPE_DOWN == key) {
                    mSwipeDownAction = sharedPreferences.getString(KEY_SWIPE_DOWN,
                            Integer.toString(ACTION_NONE)).toInt()
                } else if (KEY_SWIPE_LEFT == key) {
                    mSwipeLeftAction = sharedPreferences.getString(KEY_SWIPE_LEFT,
                            Integer.toString(ACTION_NONE)).toInt()
                } else if (KEY_SWIPE_RIGHT == key) {
                    mSwipeRightAction = sharedPreferences.getString(KEY_SWIPE_RIGHT,
                            Integer.toString(ACTION_NONE)).toInt()
                }
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Error loading preferences")
            }
        }
    }

    companion object {
        private const val DEBUG = false
        const val TAG = "GestureService"
        private const val KEY_SWIPE_UP = "swipe_up_action_key"
        private const val KEY_SWIPE_DOWN = "swipe_down_action_key"
        private const val KEY_SWIPE_LEFT = "swipe_left_action_key"
        private const val KEY_SWIPE_RIGHT = "swipe_right_action_key"
        const val KEY_GESTURE_HAPTIC_FEEDBACK = "touchscreen_gesture_haptic_feedback"
        const val GESTURE_HAPTIC_SETTINGS_VARIABLE_NAME = "OFF_GESTURE_HAPTIC_ENABLE"
        private const val GESTURE_HAPTIC_DURATION = 100
        private const val SENSOR_WAKELOCK_DURATION = 200
        private const val ACTION_NONE = 0
        private const val ACTION_CAMERA = 1
        private const val ACTION_TORCH = 2
        private const val ACTION_WAKE_DISPLAY = 3
    }
}