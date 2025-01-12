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

package com.android.mms.service;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;

import com.android.mms.service.exception.ApnException;
import com.android.net.module.util.Inet4AddressUtils;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * APN settings used for MMS transactions
 */
public class ApnSettings {

    // MMSC URL
    private final String mServiceCenter;
    // MMSC proxy address
    private final String mProxyAddress;
    // MMSC proxy port
    private final int mProxyPort;
    // Debug text for this APN: a concatenation of interesting columns of this APN
    private final String mDebugText;

    private static final String[] APN_PROJECTION = {
            Telephony.Carriers.TYPE,
            Telephony.Carriers.MMSC,
            Telephony.Carriers.MMSPROXY,
            Telephony.Carriers.MMSPORT,
            Telephony.Carriers.NAME,
            Telephony.Carriers.APN,
            Telephony.Carriers.BEARER_BITMASK,
            Telephony.Carriers.PROTOCOL,
            Telephony.Carriers.ROAMING_PROTOCOL,
            Telephony.Carriers.AUTH_TYPE,
            Telephony.Carriers.MVNO_TYPE,
            Telephony.Carriers.MVNO_MATCH_DATA,
            Telephony.Carriers.PROXY,
            Telephony.Carriers.PORT,
            Telephony.Carriers.SERVER,
            Telephony.Carriers.USER,
            Telephony.Carriers.PASSWORD,
    };
    private static final int COLUMN_TYPE = 0;
    private static final int COLUMN_MMSC = 1;
    private static final int COLUMN_MMSPROXY = 2;
    private static final int COLUMN_MMSPORT = 3;
    private static final int COLUMN_NAME = 4;
    private static final int COLUMN_APN = 5;
    private static final int COLUMN_BEARER = 6;
    private static final int COLUMN_PROTOCOL = 7;
    private static final int COLUMN_ROAMING_PROTOCOL = 8;
    private static final int COLUMN_AUTH_TYPE = 9;
    private static final int COLUMN_MVNO_TYPE = 10;
    private static final int COLUMN_MVNO_MATCH_DATA = 11;
    private static final int COLUMN_PROXY = 12;
    private static final int COLUMN_PORT = 13;
    private static final int COLUMN_SERVER = 14;
    private static final int COLUMN_USER = 15;
    private static final int COLUMN_PASSWORD = 16;


    /**
     * Load APN settings from system
     *
     * @param apnName   the optional APN name to match
     * @param requestId the request ID for logging
     */
    public static ApnSettings load(Context context, String apnName, int subId, String requestId)
            throws ApnException {
        LogUtil.i(requestId, "Loading APN using name " + apnName);
        // TODO: CURRENT semantics is currently broken in telephony. Revive this when it is fixed.
        //String selection = Telephony.Carriers.CURRENT + " IS NOT NULL";
        String selection = null;
        String[] selectionArgs = null;
        apnName = apnName != null ? apnName.trim() : null;
        if (!TextUtils.isEmpty(apnName)) {
            //selection += " AND " + Telephony.Carriers.APN + "=?";
            selection = Telephony.Carriers.APN + "=?";
            selectionArgs = new String[]{apnName};
        }

        try (Cursor cursor = context.getContentResolver().query(
                    Uri.withAppendedPath(Telephony.Carriers.SIM_APN_URI, String.valueOf(subId)),
                    APN_PROJECTION,
                    selection,
                    selectionArgs,
                    null/*sortOrder*/)) {

            ApnSettings settings = getApnSettingsFromCursor(cursor, requestId);
            if (settings != null) {
                return settings;
            }
        }
        throw new ApnException("Can not find valid APN");
    }

    private static ApnSettings getApnSettingsFromCursor(Cursor cursor, String requestId)
            throws ApnException {
        if (cursor == null) {
            return null;
        }

        // Default proxy port to 80
        int proxyPort = 80;
        while (cursor.moveToNext()) {
            // Read values from APN settings
            if (isValidApnType(
                    cursor.getString(COLUMN_TYPE), ApnSetting.TYPE_MMS_STRING)) {
                String mmscUrl = trimWithNullCheck(cursor.getString(COLUMN_MMSC));
                if (TextUtils.isEmpty(mmscUrl)) {
                    continue;
                }
                mmscUrl = Inet4AddressUtils.trimAddressZeros(mmscUrl);
                try {
                    new URI(mmscUrl);
                } catch (URISyntaxException e) {
                    throw new ApnException("Invalid MMSC url " + mmscUrl);
                }
                String proxyAddress = trimWithNullCheck(cursor.getString(COLUMN_MMSPROXY));
                if (!TextUtils.isEmpty(proxyAddress)) {
                    proxyAddress = Inet4AddressUtils.trimAddressZeros(proxyAddress);
                    final String portString =
                            trimWithNullCheck(cursor.getString(COLUMN_MMSPORT));
                    if (!TextUtils.isEmpty(portString)) {
                        try {
                            proxyPort = Integer.parseInt(portString);
                        } catch (NumberFormatException e) {
                            LogUtil.e(requestId, "Invalid port " + portString + ", use 80");
                        }
                    }
                }
                return new ApnSettings(
                        mmscUrl, proxyAddress, proxyPort, getDebugText(cursor));
            }
        }
        return null;
    }

    /**
     * Convert the APN received from network to an APN used by MMS to make http request. Essentially
     * the same as {@link #getApnSettingsFromCursor}.
     * @param apn network APN for setup network.
     * @return APN to make http request.
     */
    @Nullable
    public static ApnSettings getApnSettingsFromNetworkApn(@NonNull ApnSetting apn) {
        // Default proxy port to 80
        int proxyPort = 80;
        // URL
        if (apn.getMmsc() == null) return null;
        String mmscUrl = apn.getMmsc().toString().trim();
        if (TextUtils.isEmpty(mmscUrl)) return null;
        // proxy address
        String proxy = trimWithNullCheck(apn.getMmsProxyAddressAsString());
        if (!TextUtils.isEmpty(proxy)) {
            proxy = Inet4AddressUtils.trimAddressZeros(proxy);
            // proxy port
            if (apn.getMmsProxyPort() != -1 /*UNSPECIFIED_INT*/) proxyPort = apn.getMmsProxyPort();
        }

        return new ApnSettings(mmscUrl, proxy, proxyPort, apn.toString());
    }

    private static String getDebugText(Cursor cursor) {
        final StringBuilder sb = new StringBuilder();
        sb.append("APN [");
        for (int i = 0; i < cursor.getColumnCount(); i++) {
            final String name = cursor.getColumnName(i);
            final String value = cursor.getString(i);
            if (TextUtils.isEmpty(value)) {
                continue;
            }
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(name).append('=').append(value);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String trimWithNullCheck(String value) {
        return value != null ? value.trim() : null;
    }

    public ApnSettings(String mmscUrl, String proxyAddr, int proxyPort, String debugText) {
        mServiceCenter = mmscUrl;
        mProxyAddress = proxyAddr;
        mProxyPort = proxyPort;
        mDebugText = debugText;
    }

    public String getMmscUrl() {
        return mServiceCenter;
    }

    public String getProxyAddress() {
        return mProxyAddress;
    }

    public int getProxyPort() {
        return mProxyPort;
    }

    public boolean isProxySet() {
        return !TextUtils.isEmpty(mProxyAddress);
    }

    private static boolean isValidApnType(String types, String requestType) {
        // If APN type is unspecified, assume TYPE_ALL_STRING.
        if (TextUtils.isEmpty(types)) {
            return true;
        }
        for (String type : types.split(",")) {
            type = type.trim();
            if (type.equals(requestType) || type.equals(ApnSetting.TYPE_ALL_STRING)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return mServiceCenter + " " + mProxyAddress + " " + mProxyPort + " " + mDebugText;
    }
}
