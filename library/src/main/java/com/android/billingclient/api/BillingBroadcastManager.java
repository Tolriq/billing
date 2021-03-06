/**
 * Play Billing Library is licensed to you under the Android Software Development Kit License
 * Agreement - https://developer.android.com/studio/terms ("Agreement").  By using the Play Billing
 * Library, you agree to the terms of this Agreement.
 */

package com.android.billingclient.api;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.annotation.NonNull;
import com.android.billingclient.util.BillingHelper;
import java.util.List;

/**
 * Receiver for the "com.android.vending.billing.PURCHASES_UPDATED" broadcast generated by the
 * Google Play Store app.
 *
 * <p>It is possible that an in-app item may be acquired without the application calling
 * getBuyIntent(). For example, if the item was redeemed from within the Play Store using a
 * promotional code or other mechanism. Initiating a call to queryPurchases() after this broadcast
 * was received is a standard way to get an updated list of Purchases.
 */
class BillingBroadcastManager {

  private static final String TAG = "BillingBroadcastManager";

  /** The Intent action (PURCHASES_UPDATED) that the receiver is listening for. */
  private static final String ACTION = "com.android.vending.billing.PURCHASES_UPDATED";

  private final Context mContext;
  private final BillingBroadcastReceiver mReceiver;

  /**
   * Creates broadcast manager and subscribes to broadcast events in order to notify listener on all
   * future updates.
   *
   * <p>Note: Don't forget to call destroy once you don't need broadcast receiver (e.g. from {@link
   * Activity#onDestroy()}). Otherwise there will be a memory leak.
   *
   * @param context Reference to the context.
   * @param listener Listener for PURCHASES_UPDATED broadcast event.
   */
  BillingBroadcastManager(Context context, @NonNull PurchasesUpdatedListener listener) {
    mContext = context;
    mReceiver = new BillingBroadcastReceiver(listener);
  }

  void registerReceiver() {
    mReceiver.register(mContext, new IntentFilter(ACTION));
  }

  PurchasesUpdatedListener getListener() {
    return mReceiver.mListener;
  }

  /**
   * Clean the resources and unsubscribe from broadcast events.
   *
   * <p>Note: Call this method once you don't need broadcast receiver (e.g. from {@link
   * Activity#onDestroy()}). Otherwise there will be a memory leak.
   */
  void destroy() {
    mReceiver.unRegister(mContext);
  }

  private class BillingBroadcastReceiver extends BroadcastReceiver {
    private final PurchasesUpdatedListener mListener;
    private boolean mIsRegistered;

    private BillingBroadcastReceiver(@NonNull PurchasesUpdatedListener listener) {
      mListener = listener;
    }

    /** This method only registers receiver if it was not registered yet */
    public void register(Context context, IntentFilter filer) {
      if (!mIsRegistered) {
        context.registerReceiver(mReceiver, filer);
        mIsRegistered = true;
      }
    }

    /** This method only unregisters receiver if it was registered before to avoid exception */
    public void unRegister(Context context) {
      if (mIsRegistered) {
        context.unregisterReceiver(mReceiver);
        mIsRegistered = false;
      } else {
        BillingHelper.logWarn(TAG, "Receiver is not registered.");
      }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      int responseCode = BillingHelper.getResponseCodeFromIntent(intent, TAG);
      List<Purchase> purchases = BillingHelper.extractPurchases(intent.getExtras());
      mListener.onPurchasesUpdated(responseCode, purchases);
    }
  }
}
