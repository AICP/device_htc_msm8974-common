/*
* Copyright (C) 2016 The OmniROM Project
* Copyright (C) 2020 The Android Ice Cold Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package com.aicp.device

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Vibrator
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

class VibratorStrengthPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs), SeekBar.OnSeekBarChangeListener {
    private var mSeekBar: SeekBar? = null
    private var mOldStrength = 0
    private val mMinValue = 19
    private val mMaxValue = 31
    private val mVibrator: Vibrator
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        mOldStrength = getValue(getContext())!!.toInt()
        mSeekBar = holder.findViewById(R.id.seekbar) as SeekBar
        mSeekBar!!.setMax(mMaxValue - mMinValue)
        mSeekBar!!.setProgress(mOldStrength - mMinValue)
        mSeekBar!!.setOnSeekBarChangeListener(this)
    }

    private fun setValue(newValue: String, withFeedback: Boolean) {
        Utils.writeValue(FILE_LEVEL, newValue)
        Settings.System.putString(getContext().getContentResolver(), SETTINGS_KEY, newValue)
        if (withFeedback) {
            mVibrator.vibrate(testVibrationPattern, -1)
        }
    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int,
                          fromTouch: Boolean) {
        setValue((progress + mMinValue).toString(), true)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        // NA
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        // NA
    }

    companion object {
        private const val DEBUG = false
        private const val TAG = "VibratorStrengthPreference"
        private const val FILE_LEVEL = "/sys/devices/virtual/timed_output/vibrator/vtg_level"
        private val testVibrationPattern = longArrayOf(0, 250)
        val SETTINGS_KEY: String = DeviceSettings.Companion.KEY_SETTINGS_PREFIX + DeviceSettings.Companion.KEY_VIBSTRENGTH
        const val DEFAULT_VALUE = "22"
        val isSupported: Boolean
            get() = Utils.fileWritable(FILE_LEVEL)

        fun getValue(context: Context?): String? {
            Log.i(TAG, "reading sysfs file: $FILE_LEVEL")
            return Utils.getFileValueVibrator(FILE_LEVEL, DEFAULT_VALUE)
        }

        fun restore(context: Context) {
            if (!isSupported) {
                return
            }
            var storedValue: String = Settings.System.getString(context.getContentResolver(), SETTINGS_KEY)?: DEFAULT_VALUE
            if (DEBUG) {
                Log.d(TAG, "restore value:$storedValue")
                Log.d(TAG, "restore file:$FILE_LEVEL")
            }
            Utils.writeValue(FILE_LEVEL, storedValue)
        }
    }

    init {
        // from drivers/platform/msm/qpnp-vibrator.c
        // #define QPNP_VIB_DEFAULT_VTG_MAX	1200
        // #define QPNP_VIB_DEFAULT_VTG_MAX	3100
        mVibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        setLayoutResource(R.layout.preference_seek_bar)
    }
}
