/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService implements DataApi.DataListener,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    private static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private GoogleApiClient mGoogleApiClient;
    private static final String HIGH_TEMPERATURE = "high_temperature";
    private static final String LOW_TEMPERATURE = "low_temperature";
    private static final String WEATHER_ICON = "weather_icon";

    String highTemperature;
    String lowTemperature;
    Bitmap mWeatherIcon;

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Wearable.DataApi.addListener(mGoogleApiClient, SunshineWatchFace.this);
    }

    @Override
    public void onConnectionSuspended(int i) {}

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.e(LOG_TAG, "Google API Client connection Failed - " + connectionResult);
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED &&
                    event.getDataItem().getUri().getPath().equals("/weather")) {
                Log.d(LOG_TAG, "Hitting the JACKPOT!!!");
                //
                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                highTemperature = dataMapItem.getDataMap().getString(HIGH_TEMPERATURE);
                lowTemperature = dataMapItem.getDataMap().getString(LOW_TEMPERATURE);

                Asset icon = dataMapItem.getDataMap().getAsset(WEATHER_ICON);
                // Using NEW Thread instead of Async
                loadBitmapThread(icon);
                // Async task does not work for the 1st sync for reason unknown
                // mWeatherIcon = loadBitmapNow(icon);
            }
        }
    }

    private void loadBitmapThread(final Asset icon) {
        new Thread (new Runnable() {
            public void run() {
                if (icon == null) {return;}
                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(300, TimeUnit.DAYS.MILLISECONDS);
                if (!result.isSuccess()) {
                    Log.d(LOG_TAG, "Bitmap FAILED!");
                    return;
                }
                InputStream assetStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, icon).await().getInputStream();
                if (assetStream == null) {
                    Log.d(LOG_TAG, "Requested an unknown Asset.");
                    return;
                }
                mWeatherIcon = BitmapFactory.decodeStream(assetStream);
            }
        }).start();
    }

//    private Bitmap loadBitmap(final Asset icon) {
//        if (icon == null) {return  null;}
//        new RetrieveImage().execute(icon);
//        return BitmapFactory.decodeStream(iStream);
//    }
//    public class RetrieveImage extends AsyncTask<Asset, Void, InputStream> {
//        @Override
//        protected InputStream doInBackground(Asset... assets) {
//            return Wearable.DataApi.getFdForAsset(
//                    mGoogleApiClient, assets[0]).await().getInputStream();
//        }
//        @Override
//        protected void onPostExecute(InputStream inputStream) {
//            super.onPostExecute(inputStream);
//            iStream = inputStream;
//        }
//    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);

        Paint mBackgroundPaint;
        Paint mTextPaint;
        Paint mMinTempPaint;
        Paint mMaxTempPaint;
        Paint mDayDateTextPaint;
        Paint mLinePaint;

        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        Calendar mCalendar;
        float mYOffset;
        float mDayDateYOffset;
        float mHighYOffset;
        float mLowYOffset;
        float mIconYOffset;
        int mWeatherIconSize;

        final static float mLineYOffset = 12;
        final static float mLowXOffset = 6;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.RIGHT)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mDayDateYOffset = resources.getDimension(R.dimen.digital_daydate_y_offset);
            mHighYOffset = resources.getDimension(R.dimen.digital_high_y_offset);
            mLowYOffset = resources.getDimension(R.dimen.digital_low_y_offset);
            mIconYOffset = resources.getDimension(R.dimen.digital_icon_y_offset);

            mWeatherIconSize = (int) resources.getDimension(R.dimen.digital_icon_size);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMinTempPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mDayDateTextPaint = createTextPaint(resources.getColor(R.color.daydate_text));

            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.daydate_text));
            mLinePaint.setStyle(Paint.Style.STROKE);

            mCalendar = Calendar.getInstance();
            mWeatherIcon = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(SunshineWatchFace.this)
                .addOnConnectionFailedListener(SunshineWatchFace.this)
                .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                if (mGoogleApiClient != null &&
                        !(mGoogleApiClient.isConnected() && mGoogleApiClient.isConnecting())) {
                    mGoogleApiClient.connect(); }
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                if (mGoogleApiClient != null &&
                        (mGoogleApiClient.isConnected() || mGoogleApiClient.isConnecting())) {
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dayDateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_day_size : R.dimen.digital_day_size_round);
            float tempSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_size_round : R.dimen.digital_temp_size);

            mTextPaint.setTextSize(textSize);
            mDayDateTextPaint.setTextSize(dayDateTextSize);
            mMaxTempPaint.setTextSize(tempSize);
            mMinTempPaint.setTextSize(tempSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mTextPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
            mMaxTempPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
            mMinTempPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mDayDateTextPaint.setAntiAlias(!inAmbientMode);
                    mMaxTempPaint.setAntiAlias(!inAmbientMode);
                    mMinTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    //Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                    //        .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            Date rightNow = new Date();
            SimpleDateFormat aSDF = new SimpleDateFormat("hh:mm a");
            SimpleDateFormat rSDF = new SimpleDateFormat("hh:mm:ss");
            String text = mAmbient ? aSDF.format(rightNow) : rSDF.format(rightNow);

            if (!mAmbient) {
                long Now = System.currentTimeMillis();
                mCalendar.setTimeInMillis(Now);

                String day = SimpleDateFormat.getDateInstance(DateFormat.MEDIUM).
                        format(mCalendar.getTimeInMillis());
                float dWidth = mDayDateTextPaint.measureText(day);
                canvas.drawText(day, bounds.centerX() - dWidth / 2f, bounds.centerY(),
                        mDayDateTextPaint);

                canvas.drawLine(bounds.width() / 4, bounds.height() / 2 + mLineYOffset ,
                        bounds.width() - (bounds.width() / 4), bounds.height() / 2 + mLineYOffset,
                        mLinePaint);

                if (highTemperature != null) {
                    canvas.drawText(highTemperature, bounds.centerX(), bounds.centerY() +
                            mHighYOffset, mTextPaint);
                }
                if (lowTemperature != null) {
                    canvas.drawText(lowTemperature, bounds.centerX() + mLowXOffset, bounds.centerY() +
                            mLowYOffset, mDayDateTextPaint);
                }
                if (mWeatherIcon != null) {
                    Paint paint = new Paint();
                    canvas.drawBitmap(Bitmap.createScaledBitmap(mWeatherIcon, mWeatherIconSize,
                            mWeatherIconSize, false),
                            bounds.width() / 5, (bounds.height() / 4.0f) + mIconYOffset, paint);
                }
            }
            float tWidth = mTextPaint.measureText(text);
            canvas.drawText(text, bounds.centerX() - tWidth / 2f, mYOffset, mTextPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}